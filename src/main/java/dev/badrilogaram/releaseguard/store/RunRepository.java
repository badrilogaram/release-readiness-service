package dev.badrilogaram.releaseguard.store;

import dev.badrilogaram.releaseguard.domain.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RunRepository {
    private static final String SUMMARY = """
        SELECT r.*,
          (SELECT COUNT(*) FROM check_result c WHERE c.run_id = r.id) AS total_checks,
          (SELECT COUNT(*) FROM check_result c WHERE c.run_id = r.id AND NOT c.success) AS failed_checks
        FROM validation_run r
        """;
    private final JdbcTemplate jdbc;

    public RunRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void create(UUID id, String release, List<String> components, int window, int interval) {
        jdbc.update("""
            INSERT INTO validation_run (id,release_label,created_at,status,window_seconds,interval_seconds,component_ids)
            VALUES (?,?,?,'QUEUED',?,?,?)
            """, id.toString(), release, Instant.now().toString(), window, interval, String.join(",", components));
    }

    public void start(UUID id) {
        jdbc.update("UPDATE validation_run SET status='RUNNING',started_at=? WHERE id=? AND status='QUEUED'",
                Instant.now().toString(), id.toString());
    }

    public Optional<RunSummary> find(UUID id) {
        return jdbc.query(SUMMARY + " WHERE r.id=?", this::mapSummary, id.toString()).stream().findFirst();
    }

    public List<RunSummary> recent(int limit) {
        return jdbc.query(SUMMARY + " ORDER BY r.created_at DESC,r.id DESC LIMIT ?", this::mapSummary, limit);
    }

    public List<CheckResult> checks(UUID id) {
        return jdbc.query("SELECT * FROM check_result WHERE run_id=? ORDER BY id", (rs, n) ->
                new CheckResult(rs.getLong("id"), UUID.fromString(rs.getString("run_id")), rs.getString("component"),
                        Instant.parse(rs.getString("checked_at")), rs.getBoolean("success"), rs.getInt("http_status"),
                        rs.getLong("latency_ms"), rs.getInt("attempts"), rs.getString("message"), rs.getString("evidence")), id.toString());
    }

    public void addCheck(CheckResult c) {
        jdbc.update("""
            INSERT INTO check_result (run_id,component,checked_at,success,http_status,latency_ms,attempts,message,evidence)
            VALUES (?,?,?,?,?,?,?,?,?)
            """, c.runId().toString(), c.component(), c.checkedAt().toString(), c.success(), c.httpStatus(),
                c.latencyMs(), c.attempts(), c.message(), c.evidence());
    }

    public void finish(UUID id, RunStatus status, String message) {
        jdbc.update("UPDATE validation_run SET status=?,finished_at=?,message=? WHERE id=? AND status IN ('QUEUED','RUNNING')",
                status.name(), Instant.now().toString(), message, id.toString());
    }

    public void notification(UUID id, String status) {
        jdbc.update("UPDATE validation_run SET notification_status=? WHERE id=?", status, id.toString());
    }

    @Transactional
    public int recoverInterrupted() {
        int interruptedRuns = jdbc.update("""
            UPDATE validation_run SET status='ABORTED',finished_at=?,
            message='Process stopped before validation completed; start a new run',notification_status='NOT_REQUIRED'
            WHERE status IN ('QUEUED','RUNNING')
            """, Instant.now().toString());
        // A terminal outcome may have been persisted before delivery was recorded.
        // Do not replay mail: its delivery is uncertain after a process interruption.
        jdbc.update("""
            UPDATE validation_run SET notification_status='INTERRUPTED'
            WHERE status NOT IN ('QUEUED','RUNNING') AND notification_status='PENDING'
            """);
        return interruptedRuns;
    }

    @Transactional
    public void prune(int retain) {
        List<String> completed = jdbc.queryForList("""
            SELECT id FROM validation_run
            WHERE status NOT IN ('QUEUED','RUNNING') AND notification_status <> 'PENDING'
            ORDER BY created_at DESC,id DESC
            """, String.class);
        completed.stream().skip(retain).forEach(id -> jdbc.update("DELETE FROM validation_run WHERE id=?", id));
    }

    private RunSummary mapSummary(ResultSet rs, int row) throws SQLException {
        return new RunSummary(UUID.fromString(rs.getString("id")), rs.getString("release_label"),
                Instant.parse(rs.getString("created_at")), instant(rs.getString("started_at")),
                instant(rs.getString("finished_at")), RunStatus.valueOf(rs.getString("status")),
                rs.getInt("window_seconds"), rs.getInt("interval_seconds"),
                List.of(rs.getString("component_ids").split(",")), rs.getInt("total_checks"),
                rs.getInt("failed_checks"), rs.getString("message"), rs.getString("notification_status"));
    }

    private static Instant instant(String value) { return value == null ? null : Instant.parse(value); }
}
