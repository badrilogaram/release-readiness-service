package dev.badrilogaram.releaseguard.api;

import dev.badrilogaram.releaseguard.config.GuardProperties;
import dev.badrilogaram.releaseguard.domain.*;
import dev.badrilogaram.releaseguard.report.ExcelReportService;
import dev.badrilogaram.releaseguard.service.RunService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class RunController {
    private final RunService runs;
    private final ExcelReportService reports;
    private final GuardProperties config;
    public RunController(RunService runs, ExcelReportService reports, GuardProperties config) {
        this.runs = runs; this.reports = reports; this.config = config;
    }

    @PostMapping("/runs")
    public ResponseEntity<RunSummary> create(@Valid @RequestBody CreateRunRequest request) {
        RunSummary summary = runs.create(request);
        return ResponseEntity.accepted().location(URI.create("/api/runs/" + summary.id())).body(summary);
    }
    @GetMapping("/runs") public List<RunSummary> recent() { return runs.recent(); }
    @GetMapping("/runs/{id}") public RunDetails details(@PathVariable UUID id) { return runs.details(id); }
    @PostMapping("/runs/{id}/cancel") public RunSummary cancel(@PathVariable UUID id) { return runs.cancel(id); }

    @GetMapping("/runs/{id}/report.xlsx")
    public ResponseEntity<byte[]> report(@PathVariable UUID id) {
        RunDetails details = runs.details(id);
        if (!details.run().status().terminal()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Report is available after the run completes");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=releaseguard-" + id + ".xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(reports.generate(details.run(), details.checks()));
    }

    @GetMapping("/components")
    public List<Map<String, Object>> components() {
        return config.components().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> {
            var component = entry.getValue();
            return Map.<String, Object>of("id", entry.getKey(), "expectedStatus", component.expectedStatus(),
                    "bodyContains", component.bodyContains() == null ? "" : component.bodyContains(),
                    "timeoutMs", component.timeout().toMillis());
        }).toList();
    }
}
