package es.uma.urbanpulse.identity.controller;

import es.uma.urbanpulse.identity.dto.CreateSessionRequest;
import es.uma.urbanpulse.identity.dto.RefreshSessionRequest;
import es.uma.urbanpulse.identity.dto.SessionResponse;
import es.uma.urbanpulse.identity.service.SessionService;
import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code createSession}, {@code refreshSession} and {@code deleteCurrentSession}. */
@RestController
@RequestMapping("/api/v1/sessions")
class SessionController {

    private final SessionService sessions;

    SessionController(SessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SessionResponse signIn(
            @Valid @RequestBody CreateSessionRequest body, HttpServletRequest request) {
        return sessions.signIn(body, ClientAddress.of(request));
    }

    @PostMapping("/refresh")
    SessionResponse refresh(@Valid @RequestBody RefreshSessionRequest body) {
        return sessions.refresh(body.refreshToken());
    }

    @DeleteMapping("/current")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void signOut(@AuthenticationPrincipal AuthenticatedUser user) {
        sessions.signOut(user.sessionId());
    }
}
