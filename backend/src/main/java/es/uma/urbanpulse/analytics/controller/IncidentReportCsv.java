package es.uma.urbanpulse.analytics.controller;

import es.uma.urbanpulse.analytics.dto.IncidentReportRow;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Writes a report as CSV (RFC 4180) with the same rows as its JSON. It is built in memory and sent
 * in the response, never written to disk (Twelve-Factor VI); a report has a few dozen rows at most.
 *
 * <ul>
 *   <li>UTF-8 with a BOM, so a spreadsheet does not read the accents as another encoding.
 *   <li>Lines end in CRLF and decimals use a dot, whatever the locale of the server.
 *   <li>A group that is not available says {@value #NOT_AVAILABLE}; a missing percentile is an
 *       empty cell, never {@code 0}.
 *   <li>A text cell starting with {@code =}, {@code +}, {@code -}, {@code @}, a tab or a carriage
 *       return gets a leading {@code '}: a spreadsheet would run it as a formula (CSV injection).
 *       Numeric cells are never escaped: they cannot start with those characters.
 * </ul>
 */
final class IncidentReportCsv {

    static final String NOT_AVAILABLE = "Not available";
    static final String HEADER =
            "group,total,open,closed,rejected,resolutionHoursP50,resolutionHoursP95";

    // Written as a number: the formatter would turn the escape into an invisible character.
    private static final char BYTE_ORDER_MARK = 0xFEFF;
    private static final String LINE_END = "\r\n";
    private static final String FORMULA_STARTS = "=+-@\t\r";

    private IncidentReportCsv() {}

    static byte[] write(List<IncidentReportRow> rows) {
        var csv = new StringBuilder().append(BYTE_ORDER_MARK).append(HEADER).append(LINE_END);
        for (var row : rows) {
            csv.append(text(row.group() == null ? NOT_AVAILABLE : row.group()))
                    .append(',')
                    .append(row.total())
                    .append(',')
                    .append(row.open())
                    .append(',')
                    .append(row.closed())
                    .append(',')
                    .append(row.rejected())
                    .append(',')
                    .append(decimal(row.resolutionHoursP50()))
                    .append(',')
                    .append(decimal(row.resolutionHoursP95()))
                    .append(LINE_END);
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A text cell, escaped against formulas and then quoted if RFC 4180 requires it. */
    static String text(String value) {
        var cell =
                !value.isEmpty() && FORMULA_STARTS.indexOf(value.charAt(0)) >= 0
                        ? "'" + value
                        : value;
        if (cell.contains(",")
                || cell.contains("\"")
                || cell.contains("\r")
                || cell.contains("\n")) {
            return '"' + cell.replace("\"", "\"\"") + '"';
        }
        return cell;
    }

    // BigDecimal.toPlainString never uses an exponent or the locale's decimal comma.
    private static String decimal(Double value) {
        return value == null ? "" : BigDecimal.valueOf(value).toPlainString();
    }
}
