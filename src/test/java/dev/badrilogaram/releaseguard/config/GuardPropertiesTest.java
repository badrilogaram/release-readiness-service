package dev.badrilogaram.releaseguard.config;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.validation.Validation;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GuardPropertiesTest {
    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "http://user:password@localhost/health", "/relative",
            "http://localhost/health#fragment"})
    void rejectsDestinationsThatAreNotPlainAbsoluteHttpEndpoints(String destination) {
        assertThrows(IllegalArgumentException.class, () -> new GuardProperties.Component(
                URI.create(destination), 200, "UP", Duration.ofSeconds(1), null));
    }

    @Test
    void rejectsRelativeElasticsearchUrlWithAConfigurationError() {
        assertThrows(IllegalArgumentException.class, () ->
                new ElasticsearchProperties(true, URI.create("/relative"), "logs-*", null));
    }

    @Test
    void rejectsInvalidEvidenceDestinationsAsWellAsProbeDestinations() {
        assertThrows(IllegalArgumentException.class, () -> new GuardProperties.Component(
                URI.create("https://example.test/health"), 200, "UP", Duration.ofSeconds(1),
                URI.create("http://user:secret@example.test/logs")));
    }

    @Test
    void validatesWorkloadBoundsBeforeConfigurationCanBeUsed() {
        var component = new GuardProperties.Component(URI.create("http://localhost/health"), 200,
                "UP", Duration.ofSeconds(1), null);
        var invalid = new GuardProperties(Map.of("catalog", component), 0, 3601, 0, 0,
                11, Duration.ofMillis(100), "test-key", new GuardProperties.Schedule(false, "0 */5 * * * *", 10, 2));

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Set<String> rejected = factory.getValidator().validate(invalid).stream()
                    .map(violation -> violation.getPropertyPath().toString()).collect(Collectors.toSet());
            assertTrue(rejected.containsAll(Set.of("maxConcurrentRuns", "maxWindowSeconds", "maxChecksPerRun",
                    "retainedRuns", "maxAttempts")));
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 5001})
    void rejectsNegativeOrExcessiveRetryBackoff(long milliseconds) {
        var component = new GuardProperties.Component(URI.create("http://localhost/health"), 200,
                "UP", Duration.ofSeconds(1), null);
        assertThrows(IllegalArgumentException.class, () -> new GuardProperties(Map.of("catalog", component),
                2, 300, 300, 100, 3, Duration.ofMillis(milliseconds), "test-key",
                new GuardProperties.Schedule(false, "0 */5 * * * *", 10, 2)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../private", "logs-*/_delete_by_query", "logs?pretty=true"})
    void rejectsElasticsearchIndexValuesThatCouldChangeTheRequestPath(String index) {
        assertThrows(IllegalArgumentException.class, () ->
                new ElasticsearchProperties(true, URI.create("http://localhost:9200"), index, null));
    }
}
