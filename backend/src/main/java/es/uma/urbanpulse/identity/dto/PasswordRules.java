package es.uma.urbanpulse.identity.dto;

/** Password limits of the contract ({@code CreateRegistrationRequest.password}). */
final class PasswordRules {

    /** What bcrypt reads; anything after it would be silently ignored. */
    static final int MAX_BYTES = 72;

    private PasswordRules() {}
}
