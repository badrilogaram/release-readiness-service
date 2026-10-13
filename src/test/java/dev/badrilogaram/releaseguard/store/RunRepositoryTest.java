package dev.badrilogaram.releaseguard.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.badrilogaram.releaseguard.domain.CheckResult;
import dev.badrilogaram.releaseguard.domain.RunStatus;
import dev.badrilogaram.releaseguard.domain.RunSummary;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class RunRepositoryTest {
    private static final Instant BASE_TIME = Instant.parse("2026-10-01T00:00:00Z");
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private RunRepository repository;

    @BeforeEach
    void createIsolatedDatabase() {
        database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:schema.sql")
                .build();
        jdbc = new JdbcTemplate(database);
        repository = new RunRepository(jdbc);
    }

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.shutdown();
        }
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"PASSED", "FAILED", "CANCELLED", "ABORTED"})
    void startAndFinishPreserveTheFirstTerminalOutcome(RunStatus terminalStatus) {
        UUID id = createRun(0);
        RunSummary queued = require(id);
        assertEquals(RunStatus.QUEUED, queued.status());
        assertNull(queued.startedAt());
        assertNull(queued.finishedAt());
        assertEquals("PENDING", queued.notificationStatus());
        assertEquals(List.of("catalog", "orders"), queued.componentIds());
        assertEquals(60, queued.windowSeconds());
        assertEquals(10, queued.intervalSeconds());

        repository.start(id);
        RunSummary running = require(id);
        assertEquals(RunStatus.RUNNING, running.status());
        assertNotNull(running.startedAt());
        assertNull(running.finishedAt());
        repository.start(id);
        assertEquals(running, require(id), "Starting a running run must not reset its start time");

        repository.finish(id, terminalStatus, "Original outcome");
        RunSummary finished = require(id);
        assertEquals(terminalStatus, finished.status());
        assertEquals(running.startedAt(), finished.startedAt());
        assertNotNull(finished.finishedAt());
        assertEquals("Original outcome", finished.message());

        repository.start(id);
        assertEquals(finished, require(id), "A delayed worker must not restart a terminal run");
        repository.finish(id, terminalStatus == RunStatus.FAILED ? RunStatus.PASSED : RunStatus.FAILED,
                "Late conflicting outcome");
        assertEquals(finished, require(id), "A late completion must not overwrite a terminal outcome");
    }

    @Test
    void aQueuedRunCanBeCancelledBeforeItsWorkerStarts() {
        UUID id = createRun(0);
        repository.finish(id, RunStatus.CANCELLED, "Cancelled before dispatch");
        RunSummary cancelled = require(id);
        assertEquals(RunStatus.CANCELLED, cancelled.status());
        assertNull(cancelled.startedAt());
        assertNotNull(cancelled.finishedAt());

        repository.start(id);
        repository.finish(id, RunStatus.PASSED, "Late worker completion");
        assertEquals(cancelled, require(id));
    }

    @Test
    void summariesCountChecksPerRunAndChecksRoundTripInInsertionOrder() {
        UUID first = createRun(0);
        UUID second = createRun(1);
        assertEquals(0, require(first).totalChecks());
        assertEquals(0, require(first).failedChecks());
        repository.addCheck(check(first, "catalog", true, 200, 7, 1, "Healthy", ""));
        repository.addCheck(check(first, "orders", false, 503, 40, 3, "Unavailable", "Failure evidence"));
        repository.addCheck(check(first, "catalog", false, 0, 500, 2, "Timed out", "Timeout evidence"));
        repository.addCheck(check(second, "orders", true, 200, 2, 1, "Healthy", ""));

        assertEquals(3, require(first).totalChecks());
        assertEquals(2, require(first).failedChecks());
        assertEquals(1, require(second).totalChecks());
        assertEquals(0, require(second).failedChecks());
        assertEquals(List.of(require(second), require(first)), repository.recent(10));
        assertEquals(List.of(require(second)), repository.recent(1));
        assertTrue(repository.find(UUID.randomUUID()).isEmpty());

        List<CheckResult> checks = repository.checks(first);
        assertEquals(3, checks.size());
        assertTrue(checks.get(0).id() < checks.get(1).id());
        assertTrue(checks.get(1).id() < checks.get(2).id());
        CheckResult failed = checks.get(1);
        assertEquals(first, failed.runId());
        assertEquals("orders", failed.component());
        assertEquals(BASE_TIME, failed.checkedAt());
        assertFalse(failed.success());
        assertEquals(503, failed.httpStatus());
        assertEquals(40, failed.latencyMs());
        assertEquals(3, failed.attempts());
        assertEquals("Unavailable", failed.message());
        assertEquals("Failure evidence", failed.evidence());
    }

    @Test
    void restartAbortsOnlyActiveRunsAndPreservesSettledTerminalRuns() {
        UUID queued = createRun(0);
        UUID running = createRun(1);
        repository.start(running);
        repository.addCheck(check(running, "catalog", true, 200, 5, 1, "Healthy", ""));
        Instant startedAt = require(running).startedAt();
        Map<UUID, RunSummary> terminalSnapshots = new LinkedHashMap<>();
        int order = 2;
        for (RunStatus status : List.of(RunStatus.PASSED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.ABORTED)) {
            UUID id = createRun(order++);
            repository.start(id);
            repository.finish(id, status, "Keep this outcome");
            repository.notification(id, status == RunStatus.FAILED ? "SENT" : "NOT_REQUIRED");
            terminalSnapshots.put(id, require(id));
        }

        assertEquals(2, repository.recoverInterrupted());
        for (UUID id : List.of(queued, running)) {
            RunSummary recovered = require(id);
            assertEquals(RunStatus.ABORTED, recovered.status());
            assertEquals("NOT_REQUIRED", recovered.notificationStatus());
            assertNotNull(recovered.finishedAt());
            assertTrue(recovered.message().contains("Process stopped"));
        }
        assertNull(require(queued).startedAt());
        assertEquals(startedAt, require(running).startedAt());
        assertEquals(1, require(running).totalChecks());
        terminalSnapshots.forEach((id, snapshot) -> assertEquals(snapshot, require(id)));
        RunSummary recoveredSnapshot = require(running);
        assertEquals(0, repository.recoverInterrupted(), "Recovery must be idempotent");
        assertEquals(recoveredSnapshot, require(running));
        terminalSnapshots.forEach((id, snapshot) -> assertEquals(snapshot, require(id)));
    }

    @Test
    void pruningCascadesOnlyOldSettledRunsAndPreservesActiveAndPendingDeliveryRuns() {
        UUID queued = createRun(0);
        UUID running = createRun(1);
        repository.start(running);
        UUID pendingDelivery = createRun(2);
        repository.start(pendingDelivery);
        repository.finish(pendingDelivery, RunStatus.FAILED, "Report is being generated");
        UUID oldestSettled = settledRun(3, RunStatus.PASSED, "NOT_REQUIRED");
        UUID olderSettled = settledRun(4, RunStatus.CANCELLED, "NOT_REQUIRED");
        UUID keptFirst = settledRun(5, RunStatus.FAILED, "SENT");
        UUID keptSecond = settledRun(6, RunStatus.ABORTED, "NOT_REQUIRED");
        for (UUID id : List.of(queued, running, pendingDelivery, oldestSettled, olderSettled, keptFirst, keptSecond)) {
            repository.addCheck(check(id, "catalog", false, 503, 8, 1, "Unavailable", "Retained evidence"));
        }
        RunSummary queuedSnapshot = require(queued);
        RunSummary runningSnapshot = require(running);
        RunSummary pendingSnapshot = require(pendingDelivery);

        repository.prune(2);

        for (UUID removed : List.of(oldestSettled, olderSettled)) {
            assertTrue(repository.find(removed).isEmpty());
            assertTrue(repository.checks(removed).isEmpty(), "Deleting a run must cascade to its checks");
        }
        assertEquals(queuedSnapshot, require(queued));
        assertEquals(runningSnapshot, require(running));
        assertEquals(pendingSnapshot, require(pendingDelivery));
        for (UUID kept : List.of(queued, running, pendingDelivery, keptFirst, keptSecond)) {
            assertEquals(1, repository.checks(kept).size());
        }
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM check_result", Integer.class));

        repository.notification(pendingDelivery, "DISABLED");
        repository.prune(2);
        assertTrue(repository.find(pendingDelivery).isEmpty(), "Finalized delivery makes a run eligible for retention");
        assertTrue(repository.checks(pendingDelivery).isEmpty());
        assertEquals(queuedSnapshot, require(queued));
        assertEquals(runningSnapshot, require(running));
    }

    @Test
    void restartResolvesUncertainTerminalDeliveryWithoutRewritingOutcomeAndAllowsCleanup() {
        UUID id = createRun(0);
        repository.start(id);
        repository.addCheck(check(id, "orders", false, 500, 30, 2, "Failed", "Keep until finalized"));
        repository.finish(id, RunStatus.FAILED, "Original validation failure");
        RunSummary pending = require(id);

        repository.prune(0);
        assertEquals(pending, require(id), "Concurrent retention must not remove a report awaiting notification");
        assertEquals(1, repository.checks(id).size());

        assertEquals(0, repository.recoverInterrupted(), "Terminal delivery recovery must not count as an aborted run");
        RunSummary interrupted = require(id);
        assertEquals("INTERRUPTED", interrupted.notificationStatus());
        assertEquals(new RunSummary(pending.id(), pending.release(), pending.createdAt(), pending.startedAt(),
                pending.finishedAt(), pending.status(), pending.windowSeconds(), pending.intervalSeconds(),
                pending.componentIds(), pending.totalChecks(), pending.failedChecks(), pending.message(), "INTERRUPTED"),
                interrupted);
        assertEquals(0, repository.recoverInterrupted());
        assertEquals(interrupted, require(id), "Uncertain delivery must not be queued for replay");

        repository.prune(0);
        assertTrue(repository.find(id).isEmpty());
        assertTrue(repository.checks(id).isEmpty());
    }

    private UUID createRun(int order) {
        UUID id = UUID.randomUUID();
        repository.create(id, "release-" + order, List.of("catalog", "orders"), 60, 10);
        // Stable timestamps make retention and ordering assertions independent of clock resolution.
        jdbc.update("UPDATE validation_run SET created_at=? WHERE id=?", BASE_TIME.plusSeconds(order).toString(), id.toString());
        return id;
    }

    private UUID settledRun(int order, RunStatus status, String notification) {
        UUID id = createRun(order);
        repository.start(id);
        repository.finish(id, status, "Settled outcome");
        repository.notification(id, notification);
        return id;
    }

    private RunSummary require(UUID id) {
        return repository.find(id).orElseThrow();
    }

    private static CheckResult check(UUID id, String component, boolean success, int httpStatus,
            long latency, int attempts, String message, String evidence) {
        return new CheckResult(0, id, component, BASE_TIME, success, httpStatus, latency, attempts, message, evidence);
    }
}
