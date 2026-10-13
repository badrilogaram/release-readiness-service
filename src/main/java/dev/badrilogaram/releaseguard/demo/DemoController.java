package dev.badrilogaram.releaseguard.demo;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Profile("demo")
@RestController
@RequestMapping("/demo")
public class DemoController {
    private final Map<String, State> states = new ConcurrentHashMap<>();
    public DemoController() {
        states.put("catalog", new State(Mode.UP));
        states.put("payments", new State(Mode.DOWN));
        states.put("identity", new State(Mode.UP));
    }

    @GetMapping("/components/{id}/health")
    public ResponseEntity<Map<String, Object>> health(@PathVariable String id,
            @RequestHeader(value = "X-ReleaseGuard-Run", defaultValue = "manual") String runId) throws InterruptedException {
        State state = require(id);
        int count = state.requests.incrementAndGet();
        Mode mode = state.mode;
        if (mode == Mode.SLOW) Thread.sleep(2_000);
        boolean up = mode != Mode.DOWN && (mode != Mode.FLAKY || count % 2 == 0);
        state.logs.addFirst(Map.of("@timestamp", Instant.now().toString(), "service.name", id,
                "log.level", up ? "INFO" : "ERROR", "message", up ? "Synthetic component is ready" : "Synthetic dependency is unavailable",
                "trace.id", runId.substring(0, Math.min(runId.length(), 80))));
        while (state.logs.size() > 30) state.logs.pollLast();
        return ResponseEntity.status(up ? 200 : 503).body(Map.of("component", id, "status", up ? "UP" : "DOWN", "mode", mode));
    }

    @PostMapping("/components/{id}/mode")
    public Map<String, Object> mode(@PathVariable String id, @RequestBody ModeRequest request) {
        if (request.mode() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mode is required");
        State state = require(id); state.mode = request.mode(); state.requests.set(0);
        return Map.of("component", id, "mode", state.mode);
    }

    @GetMapping("/evidence/{id}")
    public Map<String, Object> evidence(@PathVariable String id) {
        return Map.of("source", "synthetic demo logs (not an Elasticsearch connection)", "events", List.copyOf(require(id).logs));
    }

    private State require(String id) {
        State result = states.get(id);
        if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Demo component not found");
        return result;
    }

    public enum Mode { UP, DOWN, SLOW, FLAKY }
    public record ModeRequest(Mode mode) { }
    private static final class State {
        volatile Mode mode;
        final AtomicInteger requests = new AtomicInteger();
        final ConcurrentLinkedDeque<Map<String, String>> logs = new ConcurrentLinkedDeque<>();
        State(Mode mode) { this.mode = mode; }
    }
}
