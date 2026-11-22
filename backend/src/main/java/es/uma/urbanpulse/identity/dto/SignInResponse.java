package es.uma.urbanpulse.identity.dto;

/**
 * What {@code createSession} answers: the token pair ({@code 201}), or a challenge of the second
 * factor ({@code 202}) when the role of the account needs one (#57).
 */
public sealed interface SignInResponse permits SessionResponse, MfaChallengeResponse {}
