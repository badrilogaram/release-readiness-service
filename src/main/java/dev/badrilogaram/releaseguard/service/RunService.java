package dev.badrilogaram.releaseguard.service;

import dev.badrilogaram.releaseguard.api.CreateRunRequest;
import dev.badrilogaram.releaseguard.check.ComponentChecker;
import dev.badrilogaram.releaseguard.config.GuardProperties;
import dev.badrilogaram.releaseguard.domain.*;
import dev.badrilogaram.releaseguard.notification.RunNotifier;
import dev.badrilogaram.releaseguard.store.RunRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RunService {
    private static final Logger log = LoggerFactory.getLogger(RunService.class);
    private final GuardProperties properties;
    private final RunRepository repository;
    private final ComponentChecker checker;
    private final RunNotifier notifier;
    private final MeterRegistry metrics;
    private final Semaphore capacity;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<UUID, Control> active = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    public RunService(GuardProperties properties, RunRepository repository, ComponentChecker checker,
            RunNotifier notifier, MeterRegistry metrics) {
        this.properties = properties; this.repository = repository; this.checker = checker;
        this.notifier = notifier; this.metrics = metrics;
        capacity = new Semaphore(properties.maxConcurrentRuns());
        metrics.gauge("releaseguard.runs.active", active, Map::size);
    }

    @PostConstruct
    void recover() {
        int count = repository.recoverInterrupted();
        if (count > 0) log.warn("Marked {} interrupted validation runs ABORTED", count);
    }

    public RunSummary create(CreateRunRequest request) {
        validate(request);
        if (stopping) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Service is stopping");
        if (!capacity.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Validation capacity reached; wait for a run to complete");
        UUID id = UUID.randomUUID();
        Control control = new Control();
        active.put(id, control);
        try {
            repository.create(id, request.release().strip(), request.componentIds(), request.windowSeconds(), request.intervalSeconds());
            RunSummary queued = require(id);
            workers.execute(() -> execute(id, request, control));
            return queued;
        } catch (RuntimeException failure) {
            active.remove(id); capacity.release();
            repository.finish(id, RunStatus.ABORTED, "Unable to schedule validation");
            throw failure;
        }
    }

    private void validate(CreateRunRequest request) {
        if (request.release() == null || request.release().isBlank() || request.release().length() > 80
                || request.componentIds() == null || request.componentIds().isEmpty() || request.componentIds().size() > 12
                || request.componentIds().stream().anyMatch(c -> c == null || !properties.components().containsKey(c)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supply a release and configured component IDs");
        if (new HashSet<>(request.componentIds()).size() != request.componentIds().size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Component IDs must be unique");
        if (request.windowSeconds() < 1 || request.windowSeconds() > properties.maxWindowSeconds()
                || request.intervalSeconds() < 1 || request.intervalSeconds() > request.windowSeconds())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid validation window or interval");
        long rounds = (request.windowSeconds() + (long) request.intervalSeconds() - 1) / request.intervalSeconds();
        if (rounds * request.componentIds().size() > properties.maxChecksPerRun())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Requested validation exceeds the check budget");
    }

    private void execute(UUID id, CreateRunRequest request, Control control) {
        long started = System.nanoTime();
        RunStatus status = RunStatus.ABORTED;
        String message = "Validation did not complete";
        control.thread = Thread.currentThread();
        try {
            checkCancellation(control);
            repository.start(id);
            log.info("Validation started runId={} components={}", id, request.componentIds());
            long window = TimeUnit.SECONDS.toNanos(request.windowSeconds());
            long interval = TimeUnit.SECONDS.toNanos(request.intervalSeconds());
            long next = started;
            while (next - started < window) {
                checkCancellation(control);
                long pause = next - System.nanoTime();
                if (pause > 0) TimeUnit.NANOSECONDS.sleep(pause);
                if (System.nanoTime() - started >= window) break;
                runRound(id, request.componentIds(), control);
                next += interval;
                long now = System.nanoTime();
                // Skip missed sampling slots instead of starting a burst of delayed probes.
                if (next < now) next = started + ((now - started) / interval + 1) * interval;
            }
            checkCancellation(control);
            RunSummary result = require(id);
            status = result.totalChecks() == 0 ? RunStatus.ABORTED
                    : result.failedChecks() == 0 ? RunStatus.PASSED : RunStatus.FAILED;
            message = result.totalChecks() == 0 ? "No checks completed" : result.failedChecks() == 0
                    ? "Every completed check passed" : "One or more component checks failed";
        } catch (InterruptedException interrupted) {
            status = stopping ? RunStatus.ABORTED : RunStatus.CANCELLED;
            message = stopping ? "Service stopped during validation" : "Cancelled by request";
            // Clear the worker interruption before persisting its terminal state.
            Thread.interrupted();
        } catch (Exception failure) {
            status = RunStatus.ABORTED;
            message = "Validation stopped due to an internal error";
            log.error("Validation aborted runId={} errorType={}", id, failure.getClass().getSimpleName(), failure);
        } finally {
            try {
                synchronized (control) {
                    if (control.cancelled) {
                        status = stopping ? RunStatus.ABORTED : RunStatus.CANCELLED;
                        message = stopping ? "Service stopped during validation" : "Cancelled by request";
                    }
                    Thread.interrupted();
                    repository.finish(id, status, message);
                    control.finished = true;
                }
                RunSummary summary = require(id);
                String delivery = status == RunStatus.FAILED
                        ? notifier.notifyFailure(summary, repository.checks(id)) : "NOT_REQUIRED";
                repository.notification(id, delivery);
                metrics.counter("releaseguard.runs.completed", "status", status.name()).increment();
                metrics.timer("releaseguard.run.duration").record(Duration.ofNanos(System.nanoTime() - started));
                log.info("Validation completed runId={} status={} checks={} failures={} notification={}",
                        id, status, summary.totalChecks(), summary.failedChecks(), delivery);
            } finally {
                active.remove(id); capacity.release();
                repository.prune(properties.retainedRuns());
            }
        }
    }

    private void runRound(UUID id, List<String> components, Control control) throws Exception {
        List<Future<CheckResult>> pending = new ArrayList<>();
        try {
            for (String component : components) pending.add(workers.submit(() -> checker.check(id, component)));
            for (Future<CheckResult> check : pending) {
                checkCancellation(control);
                repository.addCheck(check.get());
            }
        } finally {
            pending.forEach(future -> { if (!future.isDone()) future.cancel(true); });
        }
    }

    private static void checkCancellation(Control control) throws InterruptedException {
        if (control.cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    public RunSummary cancel(UUID id) {
        RunSummary summary = require(id);
        if (summary.status().terminal()) return summary;
        Control control = active.get(id);
        if (control != null) synchronized (control) {
            if (!control.finished) {
                control.cancelled = true;
                if (control.thread != null) control.thread.interrupt();
            }
        }
        return require(id);
    }

    public RunSummary require(UUID id) {
        return repository.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
    }
    public RunDetails details(UUID id) { return new RunDetails(require(id), repository.checks(id)); }
    public List<RunSummary> recent() { return repository.recent(20); }

    @PreDestroy
    void close() {
        stopping = true;
        active.values().forEach(control -> {
            synchronized (control) {
                if (!control.finished) {
                    control.cancelled = true;
                    if (control.thread != null) control.thread.interrupt();
                }
            }
        });
        workers.shutdown();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException interrupted) {
            workers.shutdownNow(); Thread.currentThread().interrupt();
        }
    }

    private static final class Control {
        volatile Thread thread;
        volatile boolean cancelled;
        boolean finished;
    }
}
