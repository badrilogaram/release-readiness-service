package dev.badrilogaram.releaseguard.report;

import dev.badrilogaram.releaseguard.domain.CheckResult;
import dev.badrilogaram.releaseguard.domain.RunSummary;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

/** Produces a self-contained workbook in memory; string values are never formulas. */
@Service
public class ExcelReportService {
    private static final int MAX_CELL_CHARACTERS = 32_767;
    private static final String[] CHECK_COLUMNS = {
        "Check ID", "Run ID", "Component", "Checked At (UTC)", "Success",
        "HTTP Status", "Latency (ms)", "Attempts", "Message", "Evidence"
    };

    public byte[] generate(RunSummary run, List<CheckResult> checks) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(checks, "checks");
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle wrappedStyle = workbook.createCellStyle();
            wrappedStyle.setWrapText(true);

            Sheet summary = workbook.createSheet("Summary");
            header(summary, headerStyle, "Field", "Value");
            int row = 1;
            summaryRow(summary, row++, "Run ID", run.id().toString(), wrappedStyle);
            summaryRow(summary, row++, "Release", run.release(), wrappedStyle);
            summaryRow(summary, row++, "Status", run.status().name(), wrappedStyle);
            summaryRow(summary, row++, "Created At (UTC)", timestamp(run.createdAt()), wrappedStyle);
            summaryRow(summary, row++, "Started At (UTC)", timestamp(run.startedAt()), wrappedStyle);
            summaryRow(summary, row++, "Finished At (UTC)", timestamp(run.finishedAt()), wrappedStyle);
            summaryRow(summary, row++, "Window (seconds)", run.windowSeconds(), wrappedStyle);
            summaryRow(summary, row++, "Interval (seconds)", run.intervalSeconds(), wrappedStyle);
            summaryRow(summary, row++, "Components", String.join(", ", run.componentIds()), wrappedStyle);
            summaryRow(summary, row++, "Total Checks", run.totalChecks(), wrappedStyle);
            summaryRow(summary, row++, "Failed Checks", run.failedChecks(), wrappedStyle);
            summaryRow(summary, row++, "Message", run.message(), wrappedStyle);
            summaryRow(summary, row, "Notification Status", run.notificationStatus(), wrappedStyle);
            summary.setColumnWidth(0, 25 * 256);
            summary.setColumnWidth(1, 95 * 256);
            summary.createFreezePane(0, 1);

            Sheet detail = workbook.createSheet("Checks");
            header(detail, headerStyle, CHECK_COLUMNS);
            row = 1;
            for (CheckResult check : checks) {
                Row checkRow = detail.createRow(row++);
                value(checkRow.createCell(0), check.id());
                value(checkRow.createCell(1), check.runId().toString());
                value(checkRow.createCell(2), check.component());
                value(checkRow.createCell(3), timestamp(check.checkedAt()));
                value(checkRow.createCell(4), check.success());
                value(checkRow.createCell(5), check.httpStatus());
                value(checkRow.createCell(6), check.latencyMs());
                value(checkRow.createCell(7), check.attempts());
                value(checkRow.createCell(8), check.message());
                value(checkRow.createCell(9), check.evidence());
                checkRow.getCell(8).setCellStyle(wrappedStyle);
                checkRow.getCell(9).setCellStyle(wrappedStyle);
            }
            int[] widths = {12, 38, 28, 30, 12, 14, 16, 12, 65, 100};
            for (int column = 0; column < widths.length; column++) {
                detail.setColumnWidth(column, widths[column] * 256);
            }
            detail.createFreezePane(0, 1);
            detail.setAutoFilter(new CellRangeAddress(0, Math.max(0, row - 1), 0, CHECK_COLUMNS.length - 1));
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not generate validation report", exception);
        }
    }

    private static CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        return style;
    }

    private static void header(Sheet sheet, CellStyle style, String... headings) {
        Row row = sheet.createRow(0);
        for (int column = 0; column < headings.length; column++) {
            Cell cell = row.createCell(column);
            value(cell, headings[column]);
            cell.setCellStyle(style);
        }
    }

    private static void summaryRow(Sheet sheet, int index, String label, Object content, CellStyle style) {
        Row row = sheet.createRow(index);
        value(row.createCell(0), label);
        Cell cell = row.createCell(1);
        value(cell, content);
        cell.setCellStyle(style);
    }

    private static void value(Cell cell, Object content) {
        if (content instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (content instanceof Boolean flag) {
            cell.setCellValue(flag);
        } else {
            // setCellValue(String) preserves leading =, +, -, and @ as literal text.
            cell.setCellValue(bounded(content == null ? "" : content.toString()));
        }
    }

    private static String bounded(String text) {
        if (text.length() <= MAX_CELL_CHARACTERS) {
            return text;
        }
        int end = MAX_CELL_CHARACTERS - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "\u2026";
    }

    private static String timestamp(Instant instant) {
        return instant == null ? "" : DateTimeFormatter.ISO_INSTANT.format(instant);
    }
}
