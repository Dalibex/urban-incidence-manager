package es.uma.urbanpulse.identity.service;

/**
 * The first administrator was not created and nothing changed. The message names the rule that
 * failed and never a value, so it is safe to print.
 */
public class FirstAdministratorRejectedException extends RuntimeException {

    FirstAdministratorRejectedException(String reason) {
        super(reason);
    }
}
