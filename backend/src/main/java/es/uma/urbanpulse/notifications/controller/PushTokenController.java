package es.uma.urbanpulse.notifications.controller;

import es.uma.urbanpulse.notifications.dto.PushTokenRequest;
import es.uma.urbanpulse.notifications.service.PushTokenService;
import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code createPushToken} and {@code createPushTokenDeletion} (#373). Only citizens may call them:
 * the security filters answer {@code 403} to every other role ({@code ApiAuthorizationRules}).
 */
@RestController
@RequestMapping("/api/v1")
class PushTokenController {

    private final PushTokenService pushTokens;

    PushTokenController(PushTokenService pushTokens) {
        this.pushTokens = pushTokens;
    }

    @PostMapping("/push-tokens")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void register(
            @AuthenticationPrincipal AuthenticatedUser citizen,
            @Valid @RequestBody PushTokenRequest request) {
        pushTokens.register(citizen, request.token());
    }

    @PostMapping("/push-token-deletions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@Valid @RequestBody PushTokenRequest request) {
        pushTokens.delete(request.token());
    }
}
