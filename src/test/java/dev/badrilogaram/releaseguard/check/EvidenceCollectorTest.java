package dev.badrilogaram.releaseguard.check;

import static org.junit.jupiter.api.Assertions.*;

import dev.badrilogaram.releaseguard.config.ElasticsearchProperties;
import dev.badrilogaram.releaseguard.config.GuardProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EvidenceCollectorTest {
    private LocalHttpFixture server;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setUp() throws Exception {
        server = new LocalHttpFixture();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void queriesElasticsearchByServiceAndTimeWithSelectedFieldsAndApiKey() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        server.handle("/logs-*/_search", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            method.set(exchange.getRequestMethod());
            LocalHttpFixture.respond(exchange, 200, """
                    {"hits":{"hits":[{"_source":{"service.name":"payments","message":"temporary failure",
                    "password":"db-secret","token":"token-secret","api_key":"key-secret",
                    "authorization":"Bearer bearer-secret"}}]}}
                    """);
        });
        Instant since = Instant.parse("2026-10-01T10:00:00Z");
        Instant before = Instant.now();
        var elastic = new ElasticsearchProperties(true, server.uri("/"), "logs-*", "test-elastic-key");

        String evidence = new EvidenceCollector(new HttpTransport(), elastic, json)
                .collect("payments", component(), since);

        assertEquals("POST", method.get());
        assertEquals("application/json", contentType.get());
        assertEquals("ApiKey test-elastic-key", authorization.get());
        var query = json.readTree(requestBody.get());
        assertEquals(10, query.path("size").asInt());
        assertEquals(json.readTree("[\"@timestamp\",\"service.name\",\"log.level\",\"message\",\"trace.id\"]"),
                query.path("_source"));
        assertEquals("desc", query.path("sort").path(0).path("@timestamp").asString());
        var filters = query.path("query").path("bool").path("filter");
        assertEquals("payments", filters.path(0).path("term").path("service.name").asString());
        var range = filters.path(1).path("range").path("@timestamp");
        assertEquals(since.toString(), range.path("gte").asString());
        Instant until = Instant.parse(range.path("lte").asString());
        assertFalse(until.isBefore(before));
        assertFalse(until.isAfter(Instant.now()));
        assertTrue(evidence.contains("temporary failure"), "Useful diagnostic context is retained");
        assertTrue(evidence.contains("payments"));
        for (String secret : new String[] {"db-secret", "token-secret", "key-secret", "bearer-secret"}) {
            assertFalse(evidence.contains(secret), "Evidence must redact " + secret);
        }
        assertTrue(evidence.contains("[REDACTED]"));
    }

    @Test
    void usesConfiguredDemoEvidenceWhenElasticsearchIsDisabled() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        server.handle("/evidence", exchange -> {
            method.set(exchange.getRequestMethod());
            LocalHttpFixture.respond(exchange, 200, "trace=abc123 token=local-secret service=catalog");
        });

        String evidence = disabledCollector().collect("catalog", component(), Instant.now());

        assertEquals("GET", method.get());
        assertEquals("trace=abc123 token=[REDACTED] service=catalog", evidence);
    }

    @Test
    void rejectsOversizedEvidenceInsteadOfReturningAnUnboundedPayload() throws Exception {
        server.handle("/evidence", exchange -> LocalHttpFixture.respond(exchange, 200, "x".repeat(24_001)));

        String evidence = disabledCollector().collect("catalog", component(), Instant.now());

        assertTrue(evidence.startsWith("Evidence unavailable:"));
        assertTrue(evidence.contains("ResponseTooLarge"));
        assertTrue(evidence.length() < 200);
    }

    @Test
    void unavailableEvidenceDoesNotExposeTheUpstreamErrorBody() throws Exception {
        server.handle("/evidence", exchange -> LocalHttpFixture.respond(exchange, 403,
                "internal debug: password=should-not-be-returned"));

        String evidence = disabledCollector().collect("catalog", component(), Instant.now());

        assertEquals("Evidence unavailable: HTTP 403", evidence);
    }

    @Test
    void redactsWholeQuotedCredentialValuesIncludingSpaces() {
        String evidence = "{\"password\":\"alpha beta\",\"token\":\"gamma delta\",\"message\":\"useful context\"}";

        String redacted = EvidenceCollector.redact(evidence);

        for (String secretPart : new String[] {"alpha", "beta", "gamma", "delta"}) {
            assertFalse(redacted.contains(secretPart), "No part of a quoted credential should remain");
        }
        assertEquals(2, redacted.split("\\[REDACTED\\]", -1).length - 1);
        assertTrue(redacted.contains("useful context"));
    }

    private EvidenceCollector disabledCollector() {
        return new EvidenceCollector(new HttpTransport(), new ElasticsearchProperties(false, null, null, null), json);
    }

    private GuardProperties.Component component() {
        return new GuardProperties.Component(server.uri("/health"), 200, "UP", Duration.ofSeconds(1), server.uri("/evidence"));
    }
}
