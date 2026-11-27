package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.shared.web.ApiProblemException;
import org.springframework.http.HttpStatus;

/**
 * The account reached its limit of consecutive wrong second-factor codes (#435): no code is checked
 * until an administrator resets its second factor ({@code 403}).
 */
public class MfaLockedException extends ApiProblemException {

    public MfaLockedException() {
        super(
                HttpStatus.FORBIDDEN,
                "MFA_LOCKED",
                "Too many wrong codes. Ask an administrator to reset your second factor.");
    }
}
