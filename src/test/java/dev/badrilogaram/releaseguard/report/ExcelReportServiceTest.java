package dev.badrilogaram.releaseguard.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.badrilogaram.releaseguard.domain.CheckResult;
import dev.badrilogaram.releaseguard.domain.RunStatus;
import dev.badrilogaram.releaseguard.domain.RunSummary;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ExcelReportServiceTest {
    private static final UUID RUN_ID = UUID.fromString("46d774db-7c78-42f0-8869-621b4f647b80");
    private static final Instant TIME = Instant.parse("2026-10-06T12:34:56Z");
    private final ExcelReportService service = new ExcelReportService();

    @Test
    void generatesSummaryAndTypedChecksWithExplicitUtcTimestamps() throws Exception {
        RunSummary run = summary("release-1", 2, 1);
        List<CheckResult> checks = List.of(
                new CheckResult(1, RUN_ID, "catalog", TIME, true, 200, 17, 1, "Healthy", ""),
                new CheckResult(2, RUN_ID, "orders", TIME.plusSeconds(1), false, 503, 125, 3, "Unavailable", "Trace abc"));

        try (XSSFWorkbook workbook = workbook(run, checks)) {
            assertEquals(2, workbook.getNumberOfSheets());
            Sheet summary = workbook.getSheet("Summary");
            assertEquals("release-1", summary.getRow(2).getCell(1).getStringCellValue());
            assertEquals("2026-10-06T12:34:56Z", summary.getRow(4).getCell(1).getStringCellValue());
            assertEquals(CellType.NUMERIC, summary.getRow(10).getCell(1).getCellType());
            assertEquals(2, summary.getRow(10).getCell(1).getNumericCellValue());
            assertEquals(1, summary.getRow(11).getCell(1).getNumericCellValue());

            Sheet detail = workbook.getSheet("Checks");
            assertEquals(3, detail.getPhysicalNumberOfRows());
            assertEquals("Checked At (UTC)", detail.getRow(0).getCell(3).getStringCellValue());
            assertEquals("2026-10-06T12:34:57Z", detail.getRow(2).getCell(3).getStringCellValue());
            assertEquals(CellType.BOOLEAN, detail.getRow(1).getCell(4).getCellType());
            assertTrue(detail.getRow(1).getCell(4).getBooleanCellValue());
            assertFalse(detail.getRow(2).getCell(4).getBooleanCellValue());
            for (int column : new int[] {0, 5, 6, 7}) {
                assertEquals(CellType.NUMERIC, detail.getRow(2).getCell(column).getCellType());
            }
            assertEquals(503, detail.getRow(2).getCell(5).getNumericCellValue());
            assertEquals(125, detail.getRow(2).getCell(6).getNumericCellValue());
            assertEquals(3, detail.getRow(2).getCell(7).getNumericCellValue());
        }
    }

    @Test
    void preservesFormulaLikeUserContentAsStringsAndBoundsLongCells() throws Exception {
        String formula = "=HYPERLINK(\"https://example.invalid\",\"open\")";
        CheckResult check = new CheckResult(1, RUN_ID, "+orders", TIME, false, 500, 1, 1,
                formula, "x".repeat(40_000));

        try (XSSFWorkbook workbook = workbook(summary(formula, 1, 1), List.of(check))) {
            assertEquals(formula, workbook.getSheet("Summary").getRow(2).getCell(1).getStringCellValue());
            Sheet detail = workbook.getSheet("Checks");
            assertEquals("+orders", detail.getRow(1).getCell(2).getStringCellValue());
            assertEquals(formula, detail.getRow(1).getCell(8).getStringCellValue());
            String evidence = detail.getRow(1).getCell(9).getStringCellValue();
            assertEquals(32_767, evidence.length());
            assertTrue(evidence.endsWith("\u2026"));
            workbook.forEach(sheet -> sheet.forEach(row -> row.forEach(cell ->
                    assertNotEquals(CellType.FORMULA, cell.getCellType()))));
        }
    }

    @Test
    void supportsRunsWithoutChecksOrStartedAndFinishedTimes() throws Exception {
        RunSummary queued = new RunSummary(RUN_ID, "queued", TIME, null, null, RunStatus.QUEUED,
                60, 10, List.of(), 0, 0, null, null);
        try (XSSFWorkbook workbook = workbook(queued, List.of())) {
            assertEquals(1, workbook.getSheet("Checks").getPhysicalNumberOfRows());
            Sheet summary = workbook.getSheet("Summary");
            assertEquals("", summary.getRow(5).getCell(1).getStringCellValue());
            assertEquals("", summary.getRow(6).getCell(1).getStringCellValue());
            assertEquals("", summary.getRow(12).getCell(1).getStringCellValue());
            assertEquals(0, summary.getRow(10).getCell(1).getNumericCellValue());
        }
    }

    private XSSFWorkbook workbook(RunSummary summary, List<CheckResult> checks) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(service.generate(summary, checks)));
    }

    private static RunSummary summary(String release, int total, int failed) {
        return new RunSummary(RUN_ID, release, TIME, TIME, TIME.plusSeconds(60), RunStatus.FAILED,
                60, 10, List.of("catalog", "orders"), total, failed, "Completed", "DISABLED");
    }
}
