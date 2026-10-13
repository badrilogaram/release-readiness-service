package dev.badrilogaram.releaseguard.check;

import dev.badrilogaram.releaseguard.config.GuardProperties;
import dev.badrilogaram.releaseguard.domain.CheckResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class ComponentChecker {
    private final GuardProperties properties;
    private final HttpTransport http;
    private final EvidenceCollector evidence;
    private final MeterRegistry metrics;

    public ComponentChecker(GuardProperties properties, HttpTransport http, EvidenceCollector evidence, MeterRegistry metrics) {
        this.properties = properties; this.http = http; this.evidence = evidence; this.metrics = metrics;
    }

    public CheckResult check(UUID runId, String name) throws InterruptedException {
        var config = properties.components().get(name);
        Instant checkedAt = Instant.now();
        long started = System.nanoTime();
        int status = 0, attempts = 0;
        boolean success = false;
        String message = "No response";
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            attempts = attempt;
            boolean retry;
            try {
                var request = HttpRequest.newBuilder(config.url()).timeout(config.timeout())
                        .header("X-ReleaseGuard-Run", runId.toString()).GET().build();
                var response = http.send(request, 64_000);
                status = response.status();
                boolean bodyMatches = config.bodyContains() == null || config.bodyContains().isEmpty()
                        || response.body().contains(config.bodyContains());
                success = status == config.expectedStatus() && bodyMatches;
                message = success ? "Expected status and response matched"
                        : status == config.expectedStatus() ? "Expected response text was absent"
                        : "Expected HTTP " + config.expectedStatus() + ", received " + status;
                retry = !success && (status == 408 || status == 429 || status >= 500);
            } catch (IOException failure) {
                status = 0;
                message = "Request failed: " + failure.getClass().getSimpleName();
                retry = !(failure instanceof HttpTransport.ResponseTooLargeException);
            }
            if (!retry || attempt == properties.maxAttempts()) break;
            long delay = Math.min(2_000, properties.retryBackoff().toMillis() * (1L << (attempt - 1)));
            TimeUnit.MILLISECONDS.sleep(delay);
        }
        long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        String logs = success ? "" : evidence.collect(name, config, checkedAt.minusSeconds(5));
        metrics.counter("releaseguard.checks", "component", name, "outcome", success ? "passed" : "failed").increment();
        metrics.timer("releaseguard.check.duration", "component", name).record(latency, TimeUnit.MILLISECONDS);
        return new CheckResult(0, runId, name, checkedAt, success, status, latency, attempts, message, logs);
    }
}
