package dev.badrilogaram.releaseguard.check;

import dev.badrilogaram.releaseguard.config.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.*;
import java.util.Map;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class EvidenceCollector {
    private final HttpTransport http;
    private final ElasticsearchProperties elastic;
    private final JsonMapper json;

    public EvidenceCollector(HttpTransport http, ElasticsearchProperties elastic, JsonMapper json) {
        this.http = http; this.elastic = elastic; this.json = json;
    }

    public String collect(String component, GuardProperties.Component config, Instant since) throws InterruptedException {
        try {
            HttpRequest request;
            if (elastic.enabled()) {
                URI endpoint = URI.create(elastic.url().toString().replaceAll("/$", "") + "/" + elastic.index() + "/_search");
                var query = Map.of("size", 10, "sort", List.of(Map.of("@timestamp", "desc")),
                        "_source", List.of("@timestamp", "service.name", "log.level", "message", "trace.id"),
                        "query", Map.of("bool", Map.of("filter", List.of(
                                Map.of("term", Map.of("service.name", component)),
                                Map.of("range", Map.of("@timestamp", Map.of("gte", since.toString(), "lte", Instant.now().toString())))))));
                var builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(query)));
                if (elastic.apiKey() != null && !elastic.apiKey().isBlank()) builder.header("Authorization", "ApiKey " + elastic.apiKey());
                request = builder.build();
            } else if (config.evidenceUrl() != null) {
                request = HttpRequest.newBuilder(config.evidenceUrl()).timeout(Duration.ofSeconds(2)).GET().build();
            } else {
                return "No evidence source configured";
            }
            var response = http.send(request, 24_000);
            if (response.status() != 200) return "Evidence unavailable: HTTP " + response.status();
            return redact(response.body());
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception unavailable) {
            return "Evidence unavailable: " + unavailable.getClass().getSimpleName();
        }
    }

    static String redact(String text) {
        return text.replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1[REDACTED]")
                .replaceAll("(?i)([\"']?(?:password|token|api[_-]?key)[\"']?\\s*[:=]\\s*)(?:\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|[^\\s,;}]+)", "$1[REDACTED]");
    }
}
