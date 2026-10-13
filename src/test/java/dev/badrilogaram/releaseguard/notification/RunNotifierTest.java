package dev.badrilogaram.releaseguard.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.badrilogaram.releaseguard.domain.CheckResult;
import dev.badrilogaram.releaseguard.domain.RunStatus;
import dev.badrilogaram.releaseguard.domain.RunSummary;
import dev.badrilogaram.releaseguard.report.ExcelReportService;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

class RunNotifierTest {
    private static final UUID RUN_ID = UUID.fromString("46d774db-7c78-42f0-8869-621b4f647b80");
    private static final Instant TIME = Instant.parse("2026-10-06T12:34:56Z");

    @Test
    void disabledAlertsNeedNoMailSenderOrGeneratedReport() {
        ObjectProvider<JavaMailSender> providers = provider();
        ExcelReportService reports = mock(ExcelReportService.class);
        RunNotifier notifier = new RunNotifier(new AlertsProperties(false, null, null), providers, reports);

        assertEquals("DISABLED", notifier.notifyFailure(summary(1), List.of()));
        verifyNoInteractions(providers, reports);
    }

    @Test
    void successfulRunDoesNotSendEvenWhenAlertsAreEnabled() {
        ObjectProvider<JavaMailSender> providers = provider();
        ExcelReportService reports = mock(ExcelReportService.class);
        RunNotifier notifier = new RunNotifier(enabled(), providers, reports);

        assertEquals("NOT_REQUIRED", notifier.notifyFailure(summary(0), List.of()));
        verifyNoInteractions(providers, reports);
    }

    @Test
    void sendsConfiguredRecipientsARealWorkbookWithoutCheckBodiesInEmailText() throws Exception {
        ObjectProvider<JavaMailSender> providers = provider();
        JavaMailSender sender = mock(JavaMailSender.class);
        when(providers.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        RunNotifier notifier = new RunNotifier(enabled(), providers, new ExcelReportService());
        CheckResult check = new CheckResult(1, RUN_ID, "orders", TIME, false, 503, 25, 2,
                "sensitive response body", "private diagnostic evidence");

        assertEquals("SENT", notifier.notifyFailure(summary(1), List.of(check)));

        ArgumentCaptor<MimeMessage> capture = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(capture.capture());
        MimeMessage message = capture.getValue();
        message.saveChanges();
        List<String> recipients = Arrays.stream(message.getRecipients(Message.RecipientType.TO))
                .map(address -> ((InternetAddress) address).getAddress()).toList();
        assertEquals(List.of("team@example.org", "oncall@example.org"), recipients);
        assertEquals("releaseguard@example.org", ((InternetAddress) message.getFrom()[0]).getAddress());
        assertTrue(message.getSubject().startsWith("[Release Guard] Validation failed:"));
        assertFalse(message.getSubject().contains("\n"));

        List<Part> leaves = new ArrayList<>();
        collectLeaves(message, leaves);
        Part attachment = leaves.stream().filter(part -> {
            try {
                return Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition());
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }).findFirst().orElseThrow();
        assertEquals("release-readiness-" + RUN_ID + ".xlsx", attachment.getFileName());
        assertTrue(attachment.isMimeType(RunNotifier.XLSX_CONTENT_TYPE));
        try (XSSFWorkbook workbook = new XSSFWorkbook(attachment.getInputStream())) {
            assertEquals(2, workbook.getNumberOfSheets());
            assertEquals("orders", workbook.getSheet("Checks").getRow(1).getCell(2).getStringCellValue());
        }
        Part text = leaves.stream().filter(part -> {
            try {
                return part.isMimeType("text/plain");
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }).findFirst().orElseThrow();
        String body = (String) text.getContent();
        assertTrue(body.contains("Failed checks: 1"));
        assertFalse(body.contains("sensitive response body"));
        assertFalse(body.contains("private diagnostic evidence"));
    }

    @Test
    void missingMailSenderReturnsFailureWithoutThrowing() {
        RunNotifier notifier = new RunNotifier(enabled(), provider(), mock(ExcelReportService.class));
        assertEquals("FAILED", notifier.notifyFailure(summary(1), List.of()));
    }

    @Test
    void smtpFailureIsContainedAndReturnedAsStatus() {
        ObjectProvider<JavaMailSender> providers = provider();
        JavaMailSender sender = mock(JavaMailSender.class);
        when(providers.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("SMTP unavailable")).when(sender).send(any(MimeMessage.class));
        RunNotifier notifier = new RunNotifier(enabled(), providers, new ExcelReportService());

        assertEquals("FAILED", notifier.notifyFailure(summary(1), List.of()));
    }

    private static void collectLeaves(Part part, List<Part> leaves) throws Exception {
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            for (int index = 0; index < multipart.getCount(); index++) {
                collectLeaves(multipart.getBodyPart(index), leaves);
            }
        } else {
            leaves.add(part);
        }
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JavaMailSender> provider() {
        return mock(ObjectProvider.class);
    }

    private static AlertsProperties enabled() {
        return new AlertsProperties(true, "releaseguard@example.org", List.of("team@example.org", "oncall@example.org"));
    }

    private static RunSummary summary(int failed) {
        return new RunSummary(RUN_ID, "release-1\nignored-header", TIME, TIME, TIME.plusSeconds(60),
                failed == 0 ? RunStatus.PASSED : RunStatus.FAILED, 60, 10, List.of("orders"), 2, failed,
                "Finished", "PENDING");
    }
}
