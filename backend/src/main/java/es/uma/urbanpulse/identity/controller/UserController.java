package es.uma.urbanpulse.identity.controller;

import es.uma.urbanpulse.identity.dto.CreateUserRequest;
import es.uma.urbanpulse.identity.dto.UpdateUserRequest;
import es.uma.urbanpulse.identity.dto.UserResponse;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.service.UserAdministrationService;
import es.uma.urbanpulse.identity.service.UserFilter;
import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import es.uma.urbanpulse.shared.web.MalformedRequestException;
import es.uma.urbanpulse.shared.web.PageRequests;
import es.uma.urbanpulse.shared.web.PageResponse;
import es.uma.urbanpulse.shared.web.QueryParameters;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * <p>Only administrators get past {@code @PreAuthorize}; every other role gets {@code 403}. So the
 * query is read inside the method and the bodies have no {@code @Valid}: a typed parameter or Bean
 * Validation would run before {@code @PreAuthorize} and answer {@code 400} or {@code 422} to a role
 * that should get {@code 403}, as in {@code OperationalReportController}. The service validates the
 * bodies.
 *
 * <p>What Spring cannot read at all still answers {@code 400} before the role check, to any role: a
 * {@code userId} that is not a UUID, a body that is not JSON, or a {@code role} in the body that is
 * not a {@link Role}. They reveal nothing about the users, and the rest of the API behaves the
 * same.
 */
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('ADMINISTRATOR')")
class UserController {

    // The sortable properties and the default order of listUsers in the contract.
    private static final Set<String> SORTABLE = Set.of("name", "email");
    private static final Sort BY_NAME = Sort.by("name");

    // Every query parameter of listUsers in the contract; any other one is a 400.
    private static final Set<String> LIST_PARAMETERS =
            Set.of("page", "size", "sort", "name", "email", "role");

    // The page parameters of every listing in the contract (docs/api.md, "Listings").
    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final UserAdministrationService users;

    UserController(UserAdministrationService users) {
        this.users = users;
    }

    @GetMapping
    PageResponse<UserResponse> list(@RequestParam MultiValueMap<String, String> parameters) {
        QueryParameters.requireKnownAndFilled(parameters, LIST_PARAMETERS);
        var page = number(parameters, "page", DEFAULT_PAGE, 0, Integer.MAX_VALUE);
        var size = number(parameters, "size", DEFAULT_SIZE, 1, MAX_SIZE);
        var filter =
                new UserFilter(
                        optional(parameters, "name"),
                        optional(parameters, "email"),
                        roles(parameters.get("role")));
        return users.list(
                filter,
                PageRequests.of(page, size, optional(parameters, "sort"), SORTABLE, BY_NAME));
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

    private static String optional(MultiValueMap<String, String> parameters, String name) {
        var values = parameters.get(name);
        if (values == null) {
            return null;
        }
        if (values.size() > 1) {
            throw new MalformedRequestException("The parameter " + name + " is repeated.");
        }
        return values.getFirst();
    }

    private static int number(
            MultiValueMap<String, String> parameters, String name, int absent, int min, int max) {
        var value = optional(parameters, name);
        if (value == null) {
            return absent;
        }
        try {
            var number = Integer.parseInt(value);
            if (number >= min && number <= max) {
                return number;
            }
        } catch (NumberFormatException e) {
            // Answered below, like a number out of range.
        }
        throw invalidValue(name);
    }

    private static List<Role> roles(List<String> values) {
        if (values == null) {
            return List.of();
        }
        try {
            return values.stream().map(Role::valueOf).toList();
        } catch (IllegalArgumentException e) {
            throw invalidValue("role");
        }
    }

    private static MalformedRequestException invalidValue(String name) {
        return new MalformedRequestException("Invalid value of the parameter " + name + ".");
    }
}
