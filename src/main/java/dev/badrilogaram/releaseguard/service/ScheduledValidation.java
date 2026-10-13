package dev.badrilogaram.releaseguard.service;

import dev.badrilogaram.releaseguard.api.CreateRunRequest;
import dev.badrilogaram.releaseguard.config.GuardProperties;
import java.time.Instant;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ScheduledValidation {
    private final GuardProperties properties;
    private final RunService runs;
    public ScheduledValidation(GuardProperties properties, RunService runs) {
        this.properties = properties; this.runs = runs;
    }
    @Scheduled(cron = "${releaseguard.schedule.cron}")
    public void validate() {
        if (!properties.schedule().enabled()) return;
        try {
            runs.create(new CreateRunRequest("scheduled-" + Instant.now(), properties.components().keySet().stream().sorted().toList(),
                    properties.schedule().windowSeconds(), properties.schedule().intervalSeconds()));
        } catch (ResponseStatusException unavailable) {
            LoggerFactory.getLogger(getClass()).warn("Scheduled validation skipped: {}", unavailable.getReason());
        }
    }
}
