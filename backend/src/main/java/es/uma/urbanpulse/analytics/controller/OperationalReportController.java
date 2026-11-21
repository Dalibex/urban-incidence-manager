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
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * <p>Only analysts and administrators may read it: the security filters answer {@code 403} to the
 * other roles before Spring reads the query ({@code ApiAuthorizationRules}).
 */
@RestController
@RequestMapping("/api/v1/incident-reports")
class OperationalReportController {

    static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    static final String CSV_FILE_NAME = "incident-report.csv";

    // Every query parameter of getIncidentReport in the contract; any other one is a 400.
    private static final Set<String> PARAMETERS = Set.of("from", "to", "groupBy");

    private static final String GROUPINGS =
            String.join(
                    ", ",
                    Arrays.stream(ReportGrouping.values()).map(ReportGrouping::parameter).toList());

    private final OperationalReportService reports;

    OperationalReportController(OperationalReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    IncidentReportResponse json(
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to,
            @RequestParam(required = false) String groupBy) {
        return new IncidentReportResponse(rows(parameters, from, to, groupBy));
    }

    @GetMapping(produces = "text/csv")
    ResponseEntity<byte[]> csv(
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to,
            @RequestParam(required = false) String groupBy) {
        var csv = IncidentReportCsv.write(rows(parameters, from, to, groupBy));
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(CSV_FILE_NAME).build().toString())
                .body(csv);
    }

    /**
     * The three parameters are required, but not with {@code required = true}: Spring would answer
     * a missing one with its own {@code 400}, without the {@code MALFORMED_REQUEST} code of the
     * contract. ISO.DATE_TIME parses into {@code OffsetDateTime} only with an offset, so a local
     * date-time such as {@code 2026-11-01T00:00} is a {@code 400}, never an instant in the server's
     * zone. {@code groupBy} is not a {@link ReportGrouping}: the contract writes its values in
     * lowercase, and Spring would only accept the names of the constants.
     */
    private List<IncidentReportRow> rows(
            MultiValueMap<String, String> parameters,
            OffsetDateTime from,
            OffsetDateTime to,
            String groupBy) {
        QueryParameters.requireKnownAndFilled(parameters, PARAMETERS);
        QueryParameters.requireSingle(parameters, PARAMETERS);
        var grouping =
                ReportGrouping.ofParameter(required("groupBy", groupBy))
                        .orElseThrow(
                                () ->
                                        new MalformedRequestException(
                                                "Unknown groupBy. The accepted values are "
                                                        + GROUPINGS
                                                        + "."));
        return reports.report(grouping, new ReportPeriod(instant("from", from), instant("to", to)));
    }

    private static Instant instant(String name, OffsetDateTime value) {
        return QueryParameters.requireStorable(name, required(name, value).toInstant());
    }

    private static <T> T required(String name, T value) {
        if (value == null) {
            throw new MalformedRequestException("The parameter " + name + " is required.");
        }
        return value;
    }
}
