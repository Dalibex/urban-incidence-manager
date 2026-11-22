package es.uma.urbanpulse.identity.dto;

/**
 * A pending enrolment of the second factor, as in the contract: the only time the secret leaves.
 */
public record MfaEnrollmentResponse(String otpauthUri, String secret) {

    /** Keeps the secret out of logs: with it, anyone computes the codes. */
    @Override
    public String toString() {
        return "MfaEnrollmentResponse[otpauthUri=***, secret=***]";
    }
}
