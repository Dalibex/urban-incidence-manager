package es.uma.urbanpulse.identity.controller;

import es.uma.urbanpulse.identity.dto.CreateMfaEnrollmentRequest;
import es.uma.urbanpulse.identity.dto.CreateMfaVerificationRequest;
import es.uma.urbanpulse.identity.dto.MfaEnrollmentResponse;
import es.uma.urbanpulse.identity.dto.SessionResponse;
import es.uma.urbanpulse.identity.service.MfaService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code createMfaEnrollment} and {@code createMfaVerification} (#57): the second step of a staff
 * sign-in. Public: the challenge of {@code createSession} is the credential.
 */
@RestController
@RequestMapping("/api/v1")
class MfaController {

    private final MfaService mfa;

    MfaController(MfaService mfa) {
        this.mfa = mfa;
    }

    @PostMapping("/mfa-enrollments")
    @ResponseStatus(HttpStatus.CREATED)
    MfaEnrollmentResponse enroll(@Valid @RequestBody CreateMfaEnrollmentRequest body) {
        return mfa.enroll(body.challengeToken());
    }

    @PostMapping("/mfa-verifications")
    @ResponseStatus(HttpStatus.CREATED)
    SessionResponse verify(@Valid @RequestBody CreateMfaVerificationRequest body) {
        return mfa.verify(body.challengeToken(), body.code());
    }
}
