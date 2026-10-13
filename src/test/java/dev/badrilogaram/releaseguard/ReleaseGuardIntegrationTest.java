package dev.badrilogaram.releaseguard;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.badrilogaram.releaseguard.domain.RunDetails;
import dev.badrilogaram.releaseguard.domain.RunStatus;
import dev.badrilogaram.releaseguard.domain.RunSummary;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1",
    "releaseguard.api-key=integration-key", "releaseguard.max-concurrent-runs=1",
    "releaseguard.max-window-seconds=10", "releaseguard.max-checks-per-run=10",
    "releaseguard.retry-backoff=1ms", "management.health.mail.enabled=false"
})
@ActiveProfiles("test")
@Timeout(30)
class ReleaseGuardIntegrationTest {
    private static final AtomicInteger targetStatus = new AtomicInteger(503);
    private static final HttpServer target = startTarget();
    @LocalServerPort int port;
    @Autowired JsonMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + target.getAddress().getPort();
        registry.add("releaseguard.components.sample.url", () -> base + "/health");
        registry.add("releaseguard.components.sample.expected-status", () -> 200);
        registry.add("releaseguard.components.sample.body-contains", () -> "UP");
        registry.add("releaseguard.components.sample.timeout", () -> "2s");
        registry.add("releaseguard.components.sample.evidence-url", () -> base + "/evidence");
    }

    private static HttpServer startTarget() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/health", exchange -> {
                int status = targetStatus.get();
                byte[] body = (status == 200 ? "UP" : "DOWN").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.createContext("/evidence", exchange -> {
                byte[] body = "sample error password=secret trace=demo-123".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.start();
            return server;
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }

    @AfterAll static void stopTarget() { target.stop(0); }

    @Test
    void failedRunProducesEvidenceAndWorkbookThenANewRunPassesAfterRepair() throws Exception {
        targetStatus.set(503);
        var accepted = call("POST", "/api/runs", request("=demo-release", 1), true);
        assertEquals(202, accepted.statusCode(), text(accepted));
        RunSummary created = json.readValue(accepted.body(), RunSummary.class);
        assertEquals("/api/runs/" + created.id(), accepted.headers().firstValue("Location").orElseThrow());
        RunDetails failed = awaitDone(created.id());
        assertEquals(RunStatus.FAILED, failed.run().status());
        assertEquals(1, failed.run().failedChecks());
        assertEquals(3, failed.checks().getFirst().attempts());
        assertTrue(failed.checks().getFirst().evidence().contains("[REDACTED]"));
        assertFalse(failed.checks().getFirst().evidence().contains("secret"));

        var report = call("GET", "/api/runs/" + created.id() + "/report.xlsx", null, true);
        assertEquals(200, report.statusCode());
        assertTrue(report.headers().firstValue("Content-Type").orElseThrow().contains("spreadsheetml"));
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(report.body()))) {
            assertEquals("FAILED", book.getSheet("Summary").getRow(3).getCell(1).getStringCellValue());
            assertEquals("=demo-release", book.getSheet("Summary").getRow(2).getCell(1).getStringCellValue());
            assertEquals(1, book.getSheet("Checks").getLastRowNum());
        }

        targetStatus.set(200);
        var recovery = call("POST", "/api/runs", request("demo-repaired", 1), true);
        assertEquals(202, recovery.statusCode(), text(recovery));
        RunDetails passed = awaitDone(json.readValue(recovery.body(), RunSummary.class).id());
        assertEquals(RunStatus.PASSED, passed.run().status());
        assertEquals(0, passed.run().failedChecks());
        assertEquals("NOT_REQUIRED", passed.run().notificationStatus());
        // Repairing a component never rewrites the earlier failed run.
        assertEquals(RunStatus.FAILED, details(created.id()).run().status());
        assertTrue(text(call("GET", "/actuator/prometheus", null, true)).contains("releaseguard_checks_total"));
    }

    @Test
    void rejectsUnauthorizedAndInvalidRequestsWithoutStartingWork() throws Exception {
        assertEquals(401, call("GET", "/api/runs", null, false).statusCode());
        assertEquals(401, call("GET", "/actuator/metrics", null, false).statusCode());
        assertEquals(200, call("GET", "/actuator/health", null, false).statusCode());
        assertEquals(200, call("GET", "/api/components", null, true).statusCode());
        assertEquals(404, call("GET", "/demo/components/catalog/health", null, true).statusCode());
        assertEquals(400, call("POST", "/api/runs", request("bad", 1).replace("sample", "unknown"), true).statusCode());
        assertEquals(400, call("POST", "/api/runs", request("bad", 1).replace("\"sample\"", "\"sample\",\"sample\""), true).statusCode());
        assertEquals(400, call("POST", "/api/runs", request("", 1), true).statusCode());
        assertEquals(400, call("POST", "/api/runs", request("bad", 11), true).statusCode());
        assertEquals(400, call("POST", "/api/runs", "{broken", true).statusCode());
        assertEquals(404, call("GET", "/api/runs/" + UUID.randomUUID(), null, true).statusCode());
    }

    @Test
    void activeRunRejectsPrematureReportAndExcessCapacityThenCanBeCancelled() throws Exception {
        targetStatus.set(200);
        var response = call("POST", "/api/runs", request("cancel-demo", 10), true);
        assertEquals(202, response.statusCode(), text(response));
        UUID id = json.readValue(response.body(), RunSummary.class).id();
        try {
            assertEquals(409, call("GET", "/api/runs/" + id + "/report.xlsx", null, true).statusCode());
            assertEquals(429, call("POST", "/api/runs", request("busy", 1), true).statusCode());
        } finally {
            assertEquals(200, call("POST", "/api/runs/" + id + "/cancel", "", true).statusCode());
        }
        assertEquals(RunStatus.CANCELLED, awaitDone(id).run().status());
    }

    private RunDetails awaitDone(UUID id) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < until) {
            RunDetails value = details(id);
            if (value.run().status().terminal() && !value.run().notificationStatus().equals("PENDING")) return value;
            Thread.sleep(25);
        }
        throw new AssertionError("Run did not finish: " + id);
    }

    private RunDetails details(UUID id) throws Exception {
        var result = call("GET", "/api/runs/" + id, null, true);
        assertEquals(200, result.statusCode(), text(result));
        return json.readValue(result.body(), RunDetails.class);
    }

    private HttpResponse<byte[]> call(String method, String path, String body, boolean authorized) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (authorized) builder.header("X-API-Key", "integration-key");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String request(String release, int window) {
        return "{\"release\":\"" + release + "\",\"componentIds\":[\"sample\"],\"windowSeconds\":" + window + ",\"intervalSeconds\":1}";
    }
    private static String text(HttpResponse<byte[]> response) { return new String(response.body(), StandardCharsets.UTF_8); }
}
