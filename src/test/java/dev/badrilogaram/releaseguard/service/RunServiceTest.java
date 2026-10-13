package dev.badrilogaram.releaseguard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.badrilogaram.releaseguard.api.CreateRunRequest;
import dev.badrilogaram.releaseguard.check.ComponentChecker;
import dev.badrilogaram.releaseguard.config.GuardProperties;
import dev.badrilogaram.releaseguard.domain.CheckResult;
import dev.badrilogaram.releaseguard.domain.RunStatus;
import dev.badrilogaram.releaseguard.domain.RunSummary;
import dev.badrilogaram.releaseguard.notification.RunNotifier;
import dev.badrilogaram.releaseguard.store.RunRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.web.server.ResponseStatusException;

@Timeout(20)
class RunServiceTest {
    private EmbeddedDatabase database;
    private RunRepository repository;
    private ComponentChecker checker;
    private RunNotifier notifier;
    private SimpleMeterRegistry metrics;
    private RunService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("classpath:schema.sql").build();
        repository = new RunRepository(new JdbcTemplate(database));
        checker = mock(ComponentChecker.class);
        notifier = mock(RunNotifier.class);
        metrics = new SimpleMeterRegistry();
        service = new RunService(properties(), repository, checker, notifier, metrics);
        service.recover();
    }

    @AfterEach
    void tearDown() {
        if (service != null) service.close();
        if (database != null) database.shutdown();
        if (metrics != null) metrics.close();
    }

    @Test
    void cancellationInterruptsTheProbePersistsItsOutcomeAndReleasesCapacity() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        when(checker.check(any(UUID.class), anyString())).thenAnswer(call -> {
            if (invocations.incrementAndGet() == 1) blockUntilInterrupted(entered, interrupted);
            return passed(call.getArgument(0));
        });

        RunSummary first = service.create(request("blocked-release", 30));
        assertTrue(entered.await(5, TimeUnit.SECONDS), "The first component check did not start");
        assertEquals(RunStatus.RUNNING, service.require(first.id()).status());

        ResponseStatusException busy = assertThrows(ResponseStatusException.class,
                () -> service.create(request("excess-release", 1)));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, busy.getStatusCode());
        assertEquals(1, repository.recent(10).size(), "A rejected request must not create a stored run");

        service.cancel(first.id());
        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "Cancellation did not interrupt the component check");
        RunSummary cancelled = awaitFinished(first.id());
        assertEquals(RunStatus.CANCELLED, cancelled.status());
        assertEquals(0, cancelled.totalChecks());
        assertEquals("NOT_REQUIRED", cancelled.notificationStatus());
        assertNotNull(cancelled.finishedAt());
        assertEquals(RunStatus.CANCELLED, service.cancel(first.id()).status(),
                "Cancelling a terminal run should be idempotent");

        // Terminal persistence precedes permit release; allow that short asynchronous cleanup to finish.
        RunSummary next = createWhenCapacityReturns(request("next-release", 1));
        RunSummary completed = awaitFinished(next.id());
        assertEquals(RunStatus.PASSED, completed.status());
        assertEquals(1, completed.totalChecks());
        assertEquals(0, completed.failedChecks());
        verifyNoInteractions(notifier);
    }

    @Test
    void shutdownInterruptsTheProbeAndPersistsAbortedInsteadOfPassedOrCancelled() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(checker.check(any(UUID.class), anyString())).thenAnswer(call -> {
            blockUntilInterrupted(entered, interrupted);
            return passed(call.getArgument(0));
        });

        RunSummary run = service.create(request("stopped-release", 30));
        assertTrue(entered.await(5, TimeUnit.SECONDS), "The component check did not start");
        service.close();

        assertTrue(interrupted.await(1, TimeUnit.SECONDS), "Shutdown did not interrupt the component check");
        RunSummary stopped = service.require(run.id());
        assertEquals(RunStatus.ABORTED, stopped.status());
        assertEquals("Service stopped during validation", stopped.message());
        assertEquals("NOT_REQUIRED", stopped.notificationStatus());
        assertEquals(0, stopped.totalChecks());
        assertNotNull(stopped.finishedAt());
        assertEquals(0.0, metrics.get("releaseguard.runs.active").gauge().value());

        ResponseStatusException stopping = assertThrows(ResponseStatusException.class,
                () -> service.create(request("too-late-release", 1)));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, stopping.getStatusCode());
        assertEquals(1, repository.recent(10).size());
        verifyNoInteractions(notifier);
    }

    private static void blockUntilInterrupted(CountDownLatch entered, CountDownLatch interrupted)
            throws InterruptedException {
        entered.countDown();
        try {
            if (!new CountDownLatch(1).await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Blocked component check was not interrupted before its test deadline");
            }
        } catch (InterruptedException cancelled) {
            interrupted.countDown();
            throw cancelled;
        }
    }

    private RunSummary awaitFinished(UUID id) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        RunSummary result;
        do {
            result = service.require(id);
            if (result.status().terminal() && !"PENDING".equals(result.notificationStatus())) return result;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Run did not finish: " + result);
    }

    private RunSummary createWhenCapacityReturns(CreateRunRequest request) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            try {
                return service.create(request);
            } catch (ResponseStatusException busy) {
                if (!HttpStatus.TOO_MANY_REQUESTS.equals(busy.getStatusCode())) throw busy;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Run capacity was not released after cancellation");
    }

    private static CheckResult passed(UUID runId) {
        return new CheckResult(0, runId, "catalog", Instant.now(), true, 200, 1, 1,
                "Expected status and response matched", "");
    }

    private static CreateRunRequest request(String release, int windowSeconds) {
        return new CreateRunRequest(release, List.of("catalog"), windowSeconds, 1);
    }

    private static GuardProperties properties() {
        return new GuardProperties(Map.of("catalog", new GuardProperties.Component(
                URI.create("http://127.0.0.1:1/health"), 200, "UP", Duration.ofSeconds(1), null)),
                1, 60, 60, 10, 1, Duration.ZERO, "test-api-key",
                new GuardProperties.Schedule(false, "0 */5 * * * *", 10, 1));
    }
}
