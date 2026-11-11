package es.uma.urbanpulse.identity.controller;

import es.uma.urbanpulse.identity.dto.CreatePasswordChangeRequest;
import es.uma.urbanpulse.identity.service.PasswordChangeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code createPasswordChange}: public, the current password is the credential (#165). */
@RestController
@RequestMapping("/api/v1/password-changes")
class PasswordChangeController {

    private final PasswordChangeService passwordChanges;

    PasswordChangeController(PasswordChangeService passwordChanges) {
        this.passwordChanges = passwordChanges;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(
            @Valid @RequestBody CreatePasswordChangeRequest body, HttpServletRequest request) {
        passwordChanges.changePassword(body, ClientAddress.of(request));
    }
}
