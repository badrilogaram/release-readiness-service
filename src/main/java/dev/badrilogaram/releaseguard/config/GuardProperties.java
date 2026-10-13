package dev.badrilogaram.releaseguard.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("releaseguard")
public record GuardProperties(@NotEmpty Map<@Pattern(regexp = "[a-z][a-z0-9-]{0,39}") String, @Valid Component> components,
        @Min(1) @Max(16) int maxConcurrentRuns, @Min(1) @Max(3600) int maxWindowSeconds,
        @Min(1) @Max(10000) int maxChecksPerRun, @Min(1) @Max(10000) int retainedRuns,
        @Min(1) @Max(10) int maxAttempts, @NotNull Duration retryBackoff,
        @NotBlank String apiKey, @Valid @NotNull Schedule schedule) {
    public GuardProperties {
        if (components != null) components = Map.copyOf(components);
        if (retryBackoff != null && (retryBackoff.isNegative() || retryBackoff.compareTo(Duration.ofSeconds(5)) > 0))
            throw new IllegalArgumentException("Retry backoff must be between zero and five seconds");
    }

    public record Component(@NotNull URI url, @Min(100) @Max(599) int expectedStatus,
            String bodyContains, @NotNull Duration timeout, URI evidenceUrl) {
        public Component {
            validateUri(url);
            if (evidenceUrl != null) validateUri(evidenceUrl);
            if (timeout != null && (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(10)) > 0))
                throw new IllegalArgumentException("Component timeout must be between zero and ten seconds");
        }
        private static void validateUri(URI uri) {
            if (uri != null && (uri.getScheme() == null || !java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null))
                throw new IllegalArgumentException("Component URLs must be absolute HTTP(S) URLs without credentials or fragments");
        }
    }

    public record Schedule(boolean enabled, @NotBlank String cron,
            @Min(1) int windowSeconds, @Min(1) int intervalSeconds) { }
}
