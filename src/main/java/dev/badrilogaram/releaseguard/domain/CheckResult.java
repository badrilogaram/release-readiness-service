package dev.badrilogaram.releaseguard.domain;

import java.time.Instant;
import java.util.UUID;

public record CheckResult(long id, UUID runId, String component, Instant checkedAt,
        boolean success, int httpStatus, long latencyMs, int attempts, String message,
        String evidence) {
}
