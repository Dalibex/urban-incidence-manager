package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.shared.web.ApiProblemException;
import org.springframework.http.HttpStatus;

/** Right credentials, but an administrator disabled the account ({@code 403}). */
public class AccountDisabledException extends ApiProblemException {

    public AccountDisabledException() {
        super(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "This account is disabled.");
    }
}
