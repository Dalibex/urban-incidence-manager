package es.uma.urbanpulse.analytics.controller;

import static org.assertj.core.api.Assertions.assertThat;

import es.uma.urbanpulse.analytics.dto.IncidentReportRow;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The CSV of a report: format, absent values and escaping (#42). */
class IncidentReportCsvTest {

    private static final String BOM = String.valueOf((char) 0xFEFF);

    @Test
    void writesTheHeaderAndOneLinePerRowWithCrlfAndADotDecimal() {
        var csv =
                csv(
                        List.of(
                                new IncidentReportRow("Street lighting", 12, 5, 6, 1, 18.5, 40.25),
                                new IncidentReportRow("Waste", 2, 0, 2, 0, 3.0, 7.0)));

        assertThat(csv)
                .isEqualTo(
                        BOM
                                + IncidentReportCsv.HEADER
                                + "\r\n"
                                + "Street lighting,12,5,6,1,18.5,40.25\r\n"
                                + "Waste,2,0,2,0,3.0,7.0\r\n");
    }

    @Test
    void aReportWithoutRowsIsOnlyItsHeader() {
        assertThat(csv(List.of())).isEqualTo(BOM + IncidentReportCsv.HEADER + "\r\n");
    }

    @Test
    void aGroupThatIsNotAvailableAndMissingPercentilesAreNeverZero() {
        var csv = csv(List.of(new IncidentReportRow(null, 3, 3, 0, 0, null, null)));

        assertThat(csv).endsWith("\r\nNot available,3,3,0,0,,\r\n");
    }

    @Test
    void isEncodedInUtf8WithAByteOrderMark() {
        var bytes =
                IncidentReportCsv.write(
                        List.of(new IncidentReportRow("Árboles", 1, 1, 0, 0, null, null)));

        assertThat(bytes).startsWith(0xEF, 0xBB, 0xBF);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("Árboles,1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"=SUM(A1)", "+34 600", "-1", "@cmd", "\tTab", "\rReturn"})
    void escapesATextCellThatASpreadsheetWouldRunAsAFormula(String name) {
        // A carriage return also makes RFC 4180 quote the cell, around the escaped value.
        var unquoted = IncidentReportCsv.text(name).replaceFirst("^\"", "");

        assertThat(unquoted).startsWith("'" + name.charAt(0));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "Parks, gardens|\"Parks, gardens\"",
                "The \"big\" tree|\"The \"\"big\"\" tree\"",
                "=1,2|\"'=1,2\"",
                "Plain|Plain"
            })
    void quotesCommasAndQuotesAsRfc4180Says(String name, String cell) {
        assertThat(IncidentReportCsv.text(name)).isEqualTo(cell);
    }

    @Test
    void quotesALineBreak() {
        assertThat(IncidentReportCsv.text("Two\nlines")).isEqualTo("\"Two\nlines\"");
    }

    private static String csv(List<IncidentReportRow> rows) {
        return new String(IncidentReportCsv.write(rows), StandardCharsets.UTF_8);
    }
}
