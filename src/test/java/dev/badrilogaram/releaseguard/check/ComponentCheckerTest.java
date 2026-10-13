package dev.badrilogaram.releaseguard.check;

import static org.junit.jupiter.api.Assertions.*;

import dev.badrilogaram.releaseguard.config.ElasticsearchProperties;
import dev.badrilogaram.releaseguard.config.GuardProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ComponentCheckerTest {
    private LocalHttpFixture server;
    private SimpleMeterRegistry metrics;

    @BeforeEach
    void setUp() throws Exception {
        server = new LocalHttpFixture();
        metrics = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        server.close();
        metrics.close();
    }

    @Test
    void retriesTemporaryFailureAndRecordsTheSuccessfulAttemptCount() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> correlationId = new AtomicReference<>();
        server.handle("/health", exchange -> {
            correlationId.set(exchange.getRequestHeaders().getFirst("X-ReleaseGuard-Run"));
            boolean recovered = requests.incrementAndGet() > 1;
            LocalHttpFixture.respond(exchange, recovered ? 200 : 503, recovered ? "UP" : "Unavailable");
        });
        UUID runId = UUID.randomUUID();

        var result = checker(Duration.ofSeconds(2), 3, null).check(runId, "catalog");

        assertTrue(result.success());
        assertEquals(200, result.httpStatus());
        assertEquals(2, result.attempts());
        assertEquals(2, requests.get());
        assertEquals(runId.toString(), correlationId.get());
        assertEquals("", result.evidence());
        assertEquals(1.0, metrics.get("releaseguard.checks")
                .tags("component", "catalog", "outcome", "passed").counter().count());
    }

    @Test
    void doesNotRetryUnauthorizedResponses() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server.handle("/health", exchange -> {
            requests.incrementAndGet();
            LocalHttpFixture.respond(exchange, 401, "Unauthorized");
        });

        var result = checker(Duration.ofSeconds(2), 3, null).check(UUID.randomUUID(), "catalog");

        assertFalse(result.success());
        assertEquals(401, result.httpStatus());
        assertEquals(1, result.attempts());
        assertEquals(1, requests.get());
    }

    @Test
    void doesNotRetryAResponseWhoseStatusMatchesButBodyDoesNot() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server.handle("/health", exchange -> {
            requests.incrementAndGet();
            LocalHttpFixture.respond(exchange, 200, "DEGRADED");
        });

        var result = checker(Duration.ofSeconds(2), 3, null).check(UUID.randomUUID(), "catalog");

        assertFalse(result.success());
        assertEquals(200, result.httpStatus());
        assertEquals(1, result.attempts());
        assertEquals(1, requests.get());
        assertTrue(result.message().contains("response text was absent"));
    }

    @Test
    void returnsAFailedCheckAfterBoundedNetworkTimeoutRetries() {
        CountDownLatch releaseResponse = new CountDownLatch(1);
        server.handle("/health", exchange -> {
            try (exchange) {
                try {
                    releaseResponse.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        try {
            var result = assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    checker(Duration.ofMillis(100), 2, null).check(UUID.randomUUID(), "catalog"));
            assertFalse(result.success());
            assertEquals(0, result.httpStatus());
            assertEquals(2, result.attempts());
            assertTrue(result.message().contains("Timeout"));
        } finally {
            releaseResponse.countDown();
        }
    }

    @Test
    void oversizedResponsesFailWithoutRepeatingTheSameLargeDownload() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server.handle("/health", exchange -> {
            requests.incrementAndGet();
            LocalHttpFixture.respond(exchange, 200, "x".repeat(64_001));
        });

        var result = checker(Duration.ofSeconds(2), 3, null).check(UUID.randomUUID(), "catalog");

        assertFalse(result.success());
        assertEquals(1, result.attempts());
        assertEquals(1, requests.get());
        assertTrue(result.message().contains("ResponseTooLarge"));
    }

    @Test
    void evidenceFailureDoesNotReplaceTheOriginalHealthFailure() throws Exception {
        server.handle("/health", exchange -> LocalHttpFixture.respond(exchange, 503, "Unavailable"));
        server.handle("/evidence", exchange -> LocalHttpFixture.respond(exchange, 502, "Log store unavailable"));

        var result = checker(Duration.ofSeconds(2), 1, server.uri("/evidence"))
                .check(UUID.randomUUID(), "catalog");

        assertFalse(result.success());
        assertEquals(503, result.httpStatus());
        assertEquals(1, result.attempts());
        assertTrue(result.message().contains("received 503"));
        assertEquals("Evidence unavailable: HTTP 502", result.evidence());
        assertEquals(1.0, metrics.get("releaseguard.checks")
                .tags("component", "catalog", "outcome", "failed").counter().count());
    }

    @Test
    void interruptionOfAnInFlightProbePropagatesInsteadOfBecomingAFailedCheck() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        server.handle("/health", exchange -> {
            try (exchange) {
                requestStarted.countDown();
                try {
                    releaseResponse.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        var checker = checker(Duration.ofSeconds(4), 3, null);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                checker.check(UUID.randomUUID(), "catalog");
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });

        try {
            assertTrue(requestStarted.await(3, TimeUnit.SECONDS), "Probe reached the local server");
            worker.interrupt();
            worker.join(2_000);
            assertFalse(worker.isAlive(), "Cancellation stops waiting for the HTTP response");
            assertInstanceOf(InterruptedException.class, failure.get());
            assertNull(metrics.find("releaseguard.checks").counter(), "An interrupted probe is not a completed observation");
        } finally {
            releaseResponse.countDown();
            worker.interrupt();
            worker.join(2_000);
        }
    }

    private ComponentChecker checker(Duration timeout, int attempts, URI evidenceUrl) {
        var component = new GuardProperties.Component(server.uri("/health"), 200, "UP", timeout, evidenceUrl);
        var properties = new GuardProperties(Map.of("catalog", component), 2, 300, 300, 100,
                attempts, Duration.ofMillis(5), "test-api-key", new GuardProperties.Schedule(false, "0 */5 * * * *", 10, 2));
        var transport = new HttpTransport();
        var collector = new EvidenceCollector(transport, new ElasticsearchProperties(false, null, null, null),
                JsonMapper.builder().build());
        return new ComponentChecker(properties, transport, collector, metrics);
    }
}
