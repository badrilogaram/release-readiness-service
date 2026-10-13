package dev.badrilogaram.releaseguard.notification;

import dev.badrilogaram.releaseguard.domain.CheckResult;
import dev.badrilogaram.releaseguard.domain.RunSummary;
import dev.badrilogaram.releaseguard.report.ExcelReportService;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class RunNotifier {
    public static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final Logger log = LoggerFactory.getLogger(RunNotifier.class);

    private final AlertsProperties properties;
    private final ObjectProvider<JavaMailSender> mailSenders;
    private final ExcelReportService reports;

    public RunNotifier(AlertsProperties properties, ObjectProvider<JavaMailSender> mailSenders,
            ExcelReportService reports) {
        this.properties = properties;
        this.mailSenders = mailSenders;
        this.reports = reports;
    }

    public String notifyFailure(RunSummary run, List<CheckResult> checks) {
        if (run.failedChecks() <= 0) {
            return "NOT_REQUIRED";
        }
        if (!properties.enabled()) {
            return "DISABLED";
        }
        try {
            JavaMailSender sender = mailSenders.getIfAvailable();
            if (sender == null) {
                log.warn("Failure notification unavailable for run {}: no mail sender is configured", run.id());
                return "FAILED";
            }
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.from());
            helper.setTo(properties.to().toArray(String[]::new));
            helper.setSubject("[Release Guard] Validation failed: " + subjectRelease(run.release()));
            helper.setText("""
                    Release validation detected failed checks.

                    Run ID: %s
                    Release: %s
                    Run status: %s
                    Total checks: %d
                    Failed checks: %d
                    Finished at (UTC): %s

                    The attached Excel report contains the run summary and check results.
                    """.formatted(run.id(), subjectRelease(run.release()), run.status(), run.totalChecks(),
                    run.failedChecks(), run.finishedAt() == null ? "Not finished" : run.finishedAt().toString()));
            helper.addAttachment("release-readiness-" + run.id() + ".xlsx",
                    new ByteArrayResource(reports.generate(run, checks)), XLSX_CONTENT_TYPE);
            sender.send(message);
            return "SENT";
        } catch (Exception exception) {
            // Avoid logging mail content or SMTP exception messages that may contain sensitive data.
            log.warn("Failure notification failed for run {} ({})", run.id(), exception.getClass().getSimpleName());
            return "FAILED";
        }
    }

    private static String subjectRelease(String release) {
        if (release == null || release.isBlank()) {
            return "Unnamed release";
        }
        String clean = release.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        return clean.length() <= 200 ? clean : clean.substring(0, 200);
    }
}
