package es.uma.urbanpulse.incidents.controller;

import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.identity.repository.UserRepository;
import es.uma.urbanpulse.incidents.dto.CreateIncidentRequest;
import es.uma.urbanpulse.incidents.dto.IncidentResponse;
import es.uma.urbanpulse.incidents.mapper.IncidentMapper;
import es.uma.urbanpulse.incidents.service.IncidentService;
import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    private final IncidentService incidents;
    private final UserRepository users;

    public IncidentController(IncidentService incidents, UserRepository users) {
        this.incidents = incidents;
        this.users = users;
    }

    @PostMapping
    @PreAuthorize("hasRole('CITIZEN')")
    public ResponseEntity<IncidentResponse> createIncident(
            @Valid @RequestBody CreateIncidentRequest request,
            @AuthenticationPrincipal AuthenticatedUser currentUser) {
        User reporter =
                users.findById(currentUser.id())
                        .orElseThrow(
                                () ->
                                        new org.springframework.security.authentication
                                                .InsufficientAuthenticationException(
                                                "The user does not exist"));
        var incident = incidents.createIncident(request, reporter);
        var response = IncidentMapper.toResponse(incident);
        URI location =
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(incident.getId())
                        .toUri();
        return ResponseEntity.created(location).body(response);
    }
}
