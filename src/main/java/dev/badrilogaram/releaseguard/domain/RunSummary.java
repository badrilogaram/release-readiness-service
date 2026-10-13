package dev.badrilogaram.releaseguard.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RunSummary(UUID id, String release, Instant createdAt, Instant startedAt,
        Instant finishedAt, RunStatus status, int windowSeconds, int intervalSeconds,
        List<String> componentIds, int totalChecks, int failedChecks, String message,
        String notificationStatus) {
    public RunSummary {
        componentIds = List.copyOf(componentIds);
    }
}
