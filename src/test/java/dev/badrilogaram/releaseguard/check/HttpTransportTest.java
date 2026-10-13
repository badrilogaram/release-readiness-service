package dev.badrilogaram.releaseguard.check;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpTransportTest {
    private LocalHttpFixture server;
    private HttpTransport transport;

    @BeforeEach
    void setUp() throws Exception {
        server = new LocalHttpFixture();
        transport = new HttpTransport();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void acceptsExactlyTheByteLimitAndDecodesUtf8() throws Exception {
        String body = "é".repeat(512);
        server.handle("/bounded", exchange -> LocalHttpFixture.respond(exchange, 200, body));

        var response = transport.send(request("/bounded", Duration.ofSeconds(2)), 1024);

        assertEquals(200, response.status());
        assertEquals(body, response.body());
    }

    @Test
    void rejectsAnOversizedChunkedBodyWithoutRelyingOnContentLength() {
        server.handle("/oversized", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("x".repeat(1025).getBytes(StandardCharsets.UTF_8));
            }
        });

        assertThrows(HttpTransport.ResponseTooLargeException.class,
                () -> transport.send(request("/oversized", Duration.ofSeconds(2)), 1024));
    }

    @Test
    void timeoutCoversAResponseThatStallsAfterHeadersAndPartialBody() throws Exception {
        // Warm up the client so this regression exercises body reception, not connection startup.
        server.handle("/warmup", exchange -> LocalHttpFixture.respond(exchange, 200, "ready"));
        transport.send(request("/warmup", Duration.ofSeconds(2)), 32);
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        server.handle("/stalled", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('x');
                exchange.getResponseBody().flush();
                bodyStarted.countDown();
                try {
                    releaseBody.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertThrows(HttpTimeoutException.class,
                            () -> transport.send(request("/stalled", Duration.ofMillis(500)), 1024)));
            assertEquals(0, bodyStarted.getCount(), "The response had begun before the deadline expired");
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void doesNotFollowRedirectsToAnotherTarget() throws Exception {
        AtomicInteger redirectedRequests = new AtomicInteger();
        server.handle("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", server.uri("/other-target").toString());
            LocalHttpFixture.respond(exchange, 302, "redirect");
        });
        server.handle("/other-target", exchange -> {
            redirectedRequests.incrementAndGet();
            LocalHttpFixture.respond(exchange, 200, "UP");
        });

        var response = transport.send(request("/redirect", Duration.ofSeconds(2)), 1024);

        assertEquals(302, response.status());
        assertEquals(0, redirectedRequests.get());
    }

    private HttpRequest request(String path, Duration timeout) {
        return HttpRequest.newBuilder(server.uri(path)).timeout(timeout).GET().build();
    }
}
