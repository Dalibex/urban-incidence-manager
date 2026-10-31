package es.uma.urbanpulse.identity.controller;

import es.uma.urbanpulse.identity.dto.UserResponse;
import es.uma.urbanpulse.identity.service.AccountService;
import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code getCurrentUser}: the profile is read from the database; the token only has the id. */
@RestController
@RequestMapping("/api/v1/users/me")
class CurrentUserController {

    private final AccountService accounts;

    CurrentUserController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    UserResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        // A valid token of a user that no longer exists: answered by the security filters as 401.
        return accounts.profile(user.id())
                .orElseThrow(
                        () -> new InsufficientAuthenticationException("The user does not exist"));
    }
}
