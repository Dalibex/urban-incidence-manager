package es.uma.urbanpulse.identity.controller;

import es.uma.urbanpulse.identity.dto.CreateRegistrationRequest;
import es.uma.urbanpulse.identity.dto.UserResponse;
import es.uma.urbanpulse.identity.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code createRegistration}: public citizen sign-up. It does not sign the user in. */
@RestController
@RequestMapping("/api/v1/registrations")
class RegistrationController {

    private final AccountService accounts;

    RegistrationController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    UserResponse signUp(
            @Valid @RequestBody CreateRegistrationRequest body, HttpServletRequest request) {
        return accounts.signUp(body, ClientAddress.of(request));
    }
}
