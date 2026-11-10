package es.uma.urbanpulse.identity.controller;

import es.uma.urbanpulse.identity.dto.AuditEntryResponse;
import es.uma.urbanpulse.identity.entity.AuditAction;
import es.uma.urbanpulse.identity.service.AuditEntryFilter;
import es.uma.urbanpulse.identity.service.AuditEntryQueryService;
import es.uma.urbanpulse.shared.web.MalformedRequestException;
import es.uma.urbanpulse.shared.web.PageRequests;
import es.uma.urbanpulse.shared.web.PageResponse;
import es.uma.urbanpulse.shared.web.QueryParameters;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code listAuditEntries}: the audit log screen of administrators (#41). The trail is append-only,
 * so this collection only has {@code GET}; entries are written by the operations they record.
 */
@RestController
@RequestMapping("/api/v1/audit-entries")
@PreAuthorize("hasRole('ADMINISTRATOR')")
class AuditEntryController {

    // The sortable property and the default order of listAuditEntries in the contract.
    private static final Set<String> SORTABLE = Set.of("instant");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "instant");

    // Every query parameter of listAuditEntries in the contract; any other one is a 400.
    private static final Set<String> LIST_PARAMETERS =
            Set.of("page", "size", "sort", "from", "to", "actorId", "action");

    private final AuditEntryQueryService auditEntries;

    AuditEntryController(AuditEntryQueryService auditEntries) {
        this.auditEntries = auditEntries;
    }

    @GetMapping
    PageResponse<AuditEntryResponse> list(
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort,
            // ISO.DATE_TIME parses into OffsetDateTime only with an offset, so a local date-time
            // such as 2026-11-01T00:00 is a 400 instead of an instant in the server's zone.
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) List<AuditAction> action) {
        QueryParameters.requireKnownAndFilled(parameters, LIST_PARAMETERS);
        // Spring splits one value at its commas and turns an empty piece into null, so
        // ?action=CREATE_USER, would reach Set.copyOf as [CREATE_USER, null] and end in a 500.
        if (action != null && action.contains(null)) {
            throw new MalformedRequestException("The parameter action has an empty value.");
        }
        var filter =
                new AuditEntryFilter(
                        from == null ? null : from.toInstant(),
                        to == null ? null : to.toInstant(),
                        actorId,
                        action == null ? Set.of() : Set.copyOf(action));
        return auditEntries.list(filter, PageRequests.of(page, size, sort, SORTABLE, NEWEST_FIRST));
    }
}
