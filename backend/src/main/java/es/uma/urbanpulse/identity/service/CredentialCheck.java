package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.identity.repository.UserRepository;
import java.time.Clock;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Checks an email and a password for sign-in and for a password change. Both share one rate limit,
 * because both tell whether a password is right: with a limit each, a guesser would get twice the
 * attempts.
 */
@Component
class CredentialCheck {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AttemptLimiter limiter;

    // Compared against when the email has no account, so a wrong email takes as long as a wrong
    // password and the response time does not reveal which emails exist.
    private final String timingHash;

    CredentialCheck(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            AccountSecurityProperties properties,
            Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.limiter = new AttemptLimiter(properties.signInAttempts(), clock);
        this.timingHash = passwordEncoder.encode(RefreshTokens.generate());
    }

    /**
     * Returns the enabled account whose password is {@code password}. Attempts count per email and
     * client address and a right password gives them back, so only failures add up; once the limit
     * is reached even the right password waits for the window to end.
     *
     * <p>Not transactional on purpose: bcrypt takes about 100 ms and would hold a database
     * connection all that time. The caller re-reads the account in its own transaction if it
     * changes it.
     *
     * @throws TooManyAttemptsException when the limit is reached
     * @throws InvalidCredentialsException when the email has no account or the password is wrong
     * @throws AccountDisabledException when the password is right but the account is disabled
     */
    User verify(String normalizedEmail, String password, String clientAddress) {
        var limiterKey = normalizedEmail + "|" + clientAddress;
        limiter.acquire(limiterKey);

        var user = users.findByEmail(normalizedEmail);
        var hash = user.map(User::getPasswordHash).orElse(timingHash);
        if (!passwordEncoder.matches(password, hash) || user.isEmpty()) {
            throw new InvalidCredentialsException();
        }
        limiter.reset(limiterKey);
        if (!Boolean.TRUE.equals(user.get().getEnabled())) {
            throw new AccountDisabledException();
        }
        return user.get();
    }
}
