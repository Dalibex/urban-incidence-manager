package es.uma.urbanpulse.identity.controller;

import es.uma.urbanpulse.identity.dto.CreateUserRequest;
import es.uma.urbanpulse.identity.dto.UpdateUserRequest;
import es.uma.urbanpulse.identity.dto.UserResponse;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.service.UserAdministrationService;
import es.uma.urbanpulse.identity.service.UserFilter;
import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import es.uma.urbanpulse.shared.web.PageRequests;
import es.uma.urbanpulse.shared.web.PageResponse;
import es.uma.urbanpulse.shared.web.QueryParameters;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * {@code listUsers}, {@code getUser}, {@code createUser} and {@code updateUser} (#40). {@code GET
 * /users/me} stays in {@link CurrentUserController}: Spring prefers its literal path to {@code
 * {userId}}.
 *
 * <p>Only administrators may call them: the security filters answer {@code 403} to every other role
 * before Spring reads the request ({@code ApiAuthorizationRules}). The bodies have no
 * {@code @Valid}: the service validates them, so their Bean Validation errors and the rules of the
 * service come in one {@code 422}.
 */
@RestController
@RequestMapping("/api/v1/users")
class UserController {

    // The sortable properties and the default order of listUsers in the contract.
    private static final Set<String> SORTABLE = Set.of("name", "email");
    private static final Sort BY_NAME = Sort.by("name");

    // Every query parameter of listUsers in the contract; any other one is a 400. Only role may
    // repeat.
    private static final Set<String> LIST_PARAMETERS =
            Set.of("page", "size", "sort", "name", "email", "role");
    private static final Set<String> SINGLE_PARAMETERS =
            Set.of("page", "size", "sort", "name", "email");

    private final UserAdministrationService users;

    UserController(UserAdministrationService users) {
        this.users = users;
    }

    @GetMapping
    PageResponse<UserResponse> list(
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) List<Role> role) {
        QueryParameters.requireKnownAndFilled(parameters, LIST_PARAMETERS);
        QueryParameters.requireSingle(parameters, SINGLE_PARAMETERS);
        // Spring splits one value at its commas and turns an empty piece into null, so
        // ?role=ANALYST, would reach the filter as [ANALYST, null].
        if (role != null && role.contains(null)) {
            throw QueryParameters.invalidValue("role");
        }
        var filter = new UserFilter(name, email, role == null ? List.of() : role);
        return users.list(filter, PageRequests.of(page, size, sort, SORTABLE, BY_NAME));
    }

    @GetMapping("/{userId}")
    UserResponse get(@PathVariable UUID userId) {
        return users.get(userId);
    }

    @PostMapping
    ResponseEntity<UserResponse> create(
            @AuthenticationPrincipal AuthenticatedUser administrator,
            @RequestBody CreateUserRequest request) {
        var created = users.create(administrator.id(), request);
        var location =
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{userId}")
                        .buildAndExpand(created.id())
                        .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PatchMapping("/{userId}")
    UserResponse update(
            @AuthenticationPrincipal AuthenticatedUser administrator,
            @PathVariable UUID userId,
            @RequestBody UpdateUserRequest request) {
        return users.update(administrator.id(), userId, request);
    }
}
