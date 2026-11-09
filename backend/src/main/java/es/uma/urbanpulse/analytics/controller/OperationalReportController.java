package es.uma.urbanpulse.analytics.controller;

import es.uma.urbanpulse.analytics.dto.IncidentReportResponse;
import es.uma.urbanpulse.analytics.dto.IncidentReportRow;
import es.uma.urbanpulse.analytics.service.OperationalReportService;
import es.uma.urbanpulse.analytics.service.ReportGrouping;
import es.uma.urbanpulse.analytics.service.ReportPeriod;
import es.uma.urbanpulse.shared.web.MalformedRequestException;
import es.uma.urbanpulse.shared.web.QueryParameters;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code getIncidentReport}: one resource in two representations, chosen by {@code Accept}.
 *
 * <p>The JSON method declares no {@code produces} on purpose. Spring compares a method without it
 * as {@code *}{@code /*}, so a request without {@code Accept} or with {@code *}{@code /*} gets JSON
 * and {@code text/csv} gets the CSV. Declaring {@code application/json} on it would make both
 * methods match {@code *}{@code /*} equally, and Spring would answer {@code 500} for an ambiguous
 * mapping. Errors stay {@code application/problem+json} with either {@code Accept}.
 *
 * <p>The parameters arrive only as the raw query and are read here, inside the method. A typed
 * {@code @RequestParam} would be converted before {@code @PreAuthorize} runs, and a role that may
 * not read reports would get {@code 400} for a missing {@code from} instead of {@code 403}.
 */
@RestController
@RequestMapping("/api/v1/incident-reports")
@PreAuthorize("hasAnyRole('ANALYST', 'ADMINISTRATOR')")
class OperationalReportController {

    static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    static final String CSV_FILE_NAME = "incident-report.csv";

    // Every query parameter of getIncidentReport in the contract; any other one is a 400.
    private static final Set<String> PARAMETERS = Set.of("from", "to", "groupBy");

    private static final String GROUPINGS =
            String.join(
                    ", ",
                    Arrays.stream(ReportGrouping.values()).map(ReportGrouping::parameter).toList());

    // PostgreSQL's timestamptz cannot hold every year Java parses (+300000 is valid ISO 8601), and
    // no report is about the years outside these.
    private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59Z");

    private final OperationalReportService reports;

    OperationalReportController(OperationalReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    IncidentReportResponse json(@RequestParam MultiValueMap<String, String> parameters) {
        return new IncidentReportResponse(rows(parameters));
    }

    @GetMapping(produces = "text/csv")
    ResponseEntity<byte[]> csv(@RequestParam MultiValueMap<String, String> parameters) {
        var csv = IncidentReportCsv.write(rows(parameters));
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(CSV_FILE_NAME).build().toString())
                .body(csv);
    }

    private List<IncidentReportRow> rows(MultiValueMap<String, String> parameters) {
        QueryParameters.requireKnownAndFilled(parameters, PARAMETERS);
        var from = instant(parameters, "from");
        var to = instant(parameters, "to");
        var groupBy = single(parameters, "groupBy");
        var grouping =
                ReportGrouping.ofParameter(groupBy)
                        .orElseThrow(
                                () ->
                                        new MalformedRequestException(
                                                "Unknown groupBy. The accepted values are "
                                                        + GROUPINGS
                                                        + "."));
        return reports.report(grouping, new ReportPeriod(from, to));
    }

    /**
     * An ISO 8601 date-time with its offset. Without one ({@code 2026-11-01T00:00}) it is a {@code
     * 400}, never an instant in the server's zone.
     */
    private static Instant instant(MultiValueMap<String, String> parameters, String name) {
        var value = single(parameters, name);
        Instant instant;
        try {
            instant =
                    OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            throw invalidValue(name);
        }
        if (instant.isBefore(EARLIEST) || instant.isAfter(LATEST)) {
            throw invalidValue(name);
        }
        return instant;
    }

    private static String single(MultiValueMap<String, String> parameters, String name) {
        var values = parameters.get(name);
        if (values == null) {
            throw new MalformedRequestException("The parameter " + name + " is required.");
        }
        if (values.size() > 1) {
            throw new MalformedRequestException("The parameter " + name + " is repeated.");
        }
        return values.getFirst();
    }

    private static MalformedRequestException invalidValue(String name) {
        return new MalformedRequestException("Invalid value of the parameter " + name + ".");
    }
}
