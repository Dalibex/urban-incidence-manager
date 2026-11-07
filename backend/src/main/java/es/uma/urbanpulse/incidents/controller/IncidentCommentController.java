package es.uma.urbanpulse.incidents.controller;

import es.uma.urbanpulse.incidents.dto.CommentResponse;
import es.uma.urbanpulse.incidents.dto.CreateCommentRequest;
import es.uma.urbanpulse.incidents.service.IncidentCommentService;
import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import es.uma.urbanpulse.shared.web.PageRequests;
import es.uma.urbanpulse.shared.web.PageResponse;
import es.uma.urbanpulse.shared.web.QueryParameters;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code listIncidentComments} and {@code createIncidentComment}. Comments are internal, so only
 * staff who handle incidents reach them; which incidents each one may see is checked by {@link
 * IncidentCommentService}.
 */
@RestController
@RequestMapping("/api/v1/incidents/{incidentId}/comments")
@PreAuthorize("hasAnyRole('MUNICIPAL_OPERATOR', 'TECHNICIAN', 'ADMINISTRATOR')")
class IncidentCommentController {

    // The sortable property and the default order of listIncidentComments in the contract: oldest
    // first, as a history reads.
    private static final Set<String> SORTABLE = Set.of("createdAt");
    private static final Sort OLDEST_FIRST = Sort.by(Sort.Direction.ASC, "createdAt");

    private static final Set<String> LIST_PARAMETERS = Set.of("page", "size", "sort");

    private final IncidentCommentService comments;

    IncidentCommentController(IncidentCommentService comments) {
        this.comments = comments;
    }

    @GetMapping
    PageResponse<CommentResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID incidentId,
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        QueryParameters.requireKnownAndFilled(parameters, LIST_PARAMETERS);
        return comments.list(
                incidentId,
                user.id(),
                user.role(),
                PageRequests.of(page, size, sort, SORTABLE, OLDEST_FIRST));
    }

    /** {@code 201} without {@code Location}: a comment has no read operation of its own. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CommentResponse create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID incidentId,
            @RequestBody CreateCommentRequest request) {
        return comments.create(incidentId, user.id(), user.role(), request.text());
    }
}
