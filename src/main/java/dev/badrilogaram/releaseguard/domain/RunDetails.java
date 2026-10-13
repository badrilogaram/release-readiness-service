package dev.badrilogaram.releaseguard.domain;

import java.util.List;

public record RunDetails(RunSummary run, List<CheckResult> checks) {
    public RunDetails {
        checks = List.copyOf(checks);
    }
}
