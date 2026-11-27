package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.identity.dto.MfaEnrollmentResponse;
import es.uma.urbanpulse.identity.dto.SessionResponse;
import es.uma.urbanpulse.identity.entity.AuditAction;
import es.uma.urbanpulse.identity.entity.MfaChallenge;
import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.identity.repository.MfaChallengeRepository;
import es.uma.urbanpulse.identity.repository.UserRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The second step of a staff sign-in (#57, ADR-0021): enrolling the second factor and checking a
 * code of a challenge that {@link SessionService#signIn} created. A valid code spends the challenge
 * and opens the session in one transaction.
 *
 * <p>Both operations lock the user row before the challenge row, like the refresh of a session, so
 * two requests never wait for each other.
 */
@Service
public class MfaService {

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);

    private final UserRepository users;
    private final MfaChallengeRepository challenges;
    private final SessionService sessions;
    private final AuditService audit;
    private final MfaCodes codes;
    private final MfaSecretCipher cipher;
    private final MfaProperties properties;
    private final AttemptLimiter limiter;
    private final Clock clock;

    MfaService(
            UserRepository users,
            MfaChallengeRepository challenges,
            SessionService sessions,
            AuditService audit,
            MfaCodes codes,
            MfaSecretCipher cipher,
            MfaProperties properties,
            Clock clock) {
        this.users = users;
        this.challenges = challenges;
        this.sessions = sessions;
        this.audit = audit;
        this.codes = codes;
        this.cipher = cipher;
        this.properties = properties;
        // Its own limit, keyed by the account only: separate from the password limit, and a right
        // password does not give these attempts back.
        this.limiter = new AttemptLimiter(properties.codeAttempts(), clock);
        this.clock = clock;
    }

    /**
     * Creates a new TOTP secret for an enrolment challenge and returns it; it replaces the one a
     * previous call returned for the same challenge. The secret belongs to the challenge until a
     * valid code confirms it ({@link #verify}).
     *
     * @throws InvalidMfaChallengeException if the challenge is unknown, expired or spent
     * @throws MfaAlreadyEnrolledException if the account already has a second factor
     */
    @Transactional
    public MfaEnrollmentResponse enroll(String challengeToken) {
        var locked = lock(challengeToken);
        var user = locked.user();
        var challenge = locked.challenge();
        if (!Boolean.TRUE.equals(challenge.getEnrollment()) || user.getMfaSecret() != null) {
            throw new MfaAlreadyEnrolledException();
        }
        var secret = codes.newSecret();
        challenge.setPendingSecret(cipher.encrypt(secret, user.getId()));
        return new MfaEnrollmentResponse(
                MfaCodes.otpauthUri(properties.issuer(), user.getEmail(), secret),
                MfaCodes.base32(secret));
    }

    /**
     * Checks a code for the challenge and, if it is valid, spends it and opens a session. On an
     * enrolment challenge of an account still without a second factor, a valid code also confirms
     * the secret, records {@code ENROLL_MFA} and spends every other open challenge of the account.
     * Once the account has one, every challenge checks it.
     *
     * <p>A wrong code counts against the challenge (the last allowed one spends it), against the
     * account in each window, and in the account's count of consecutive wrong codes (#435), which
     * only a valid code or a reset sets back to zero. The wrong code that reaches {@code
     * maxConsecutiveWrongCodes} records {@code LOCK_MFA}, and from then on the code step is
     * refused. Those changes, and the challenge spent for a disabled, locked account or a role that
     * no longer needs the second factor, are committed although the request fails.
     *
     * @throws InvalidMfaChallengeException if the challenge is unknown, expired or spent, or the
     *     role of the account no longer needs the second factor
     * @throws TooManyAttemptsException if the account reached its limit of wrong codes
     * @throws AccountDisabledException if the account was disabled after the password step
     * @throws MfaLockedException if the account reached its limit of consecutive wrong codes
     * @throws InvalidMfaCodeException if the code is wrong, outside its window or already used
     */
    @Transactional(
            noRollbackFor = {
                InvalidMfaChallengeException.class,
                AccountDisabledException.class,
                MfaLockedException.class,
                InvalidMfaCodeException.class
            })
    public SessionResponse verify(String challengeToken, String code) {
        var locked = lock(challengeToken);
        var user = locked.user();
        var challenge = locked.challenge();
        var now = now();

        var limiterKey = user.getId().toString();
        limiter.acquire(limiterKey);

        if (!Boolean.TRUE.equals(user.getEnabled())) {
            challenge.setSpentAt(now);
            throw new AccountDisabledException();
        }
        if (!properties.requiredFor(user.getRole())) {
            challenge.setSpentAt(now);
            throw new InvalidMfaChallengeException();
        }
        if (isLocked(user)) {
            challenge.setSpentAt(now);
            throw new MfaLockedException();
        }

        // An enrolment challenge created while another one was being confirmed checks the confirmed
        // secret, and never replaces it with its pending one.
        var enrollment = user.getMfaSecret() == null;
        var storedSecret = enrollment ? challenge.getPendingSecret() : user.getMfaSecret();
        var step =
                storedSecret == null
                        ? OptionalLong.empty()
                        : codes.acceptedStep(
                                cipher.decrypt(storedSecret, user.getId()),
                                code,
                                user.getMfaLastTimeStep());
        if (step.isEmpty()) {
            challenge.setFailedAttempts(challenge.getFailedAttempts() + 1);
            if (challenge.getFailedAttempts() >= properties.codeAttemptsPerChallenge()) {
                challenge.setSpentAt(now);
            }
            log.warn("Wrong second-factor code for user {}", user.getId());
            countWrongCode(user, challenge, now);
            throw new InvalidMfaCodeException();
        }

        limiter.reset(limiterKey);
        user.setMfaFailedCodes(0);
        challenge.setSpentAt(now);
        if (enrollment) {
            user.setMfaSecret(storedSecret);
            // The other pending enrolments had their own secret, which is no longer the one.
            challenges.spendOpen(user.getId(), now);
            audit.record(
                    user.getId(),
                    AuditAction.ENROLL_MFA,
                    AuditTarget.user(user.getId()),
                    Map.of(),
                    Map.of());
        }
        user.setMfaLastTimeStep(step.getAsLong());
        return sessions.openSession(user, true);
    }

    /**
     * Gives the account back all its wrong-code attempts, once a reset of its second factor is
     * committed ({@link UserAdministrationService#resetMfa}): the first code of the new enrolment
     * must not wait for the attempts spent on the lost phone.
     */
    void restoreCodeAttempts(UUID userId) {
        limiter.reset(userId.toString());
    }

    private boolean isLocked(User user) {
        return user.getMfaFailedCodes() >= properties.maxConsecutiveWrongCodes();
    }

    /**
     * Adds a wrong code to the account's consecutive count. The user row is locked, so wrong codes
     * sent at once are counted one after the other and none is lost. The one that reaches the limit
     * spends the challenge and records {@code LOCK_MFA}, with the account as its own actor.
     */
    private void countWrongCode(User user, MfaChallenge challenge, OffsetDateTime now) {
        user.setMfaFailedCodes(user.getMfaFailedCodes() + 1);
        if (!isLocked(user)) {
            return;
        }
        challenge.setSpentAt(now);
        log.warn(
                "Second factor of user {} locked after {} consecutive wrong codes",
                user.getId(),
                user.getMfaFailedCodes());
        audit.record(
                user.getId(),
                AuditAction.LOCK_MFA,
                AuditTarget.user(user.getId()),
                Map.of(),
                Map.of("consecutiveWrongCodes", user.getMfaFailedCodes()));
    }

    private LockedChallenge lock(String challengeToken) {
        var tokenHash = RefreshTokens.hash(challengeToken);
        var userId =
                challenges
                        .findUserIdByTokenHash(tokenHash)
                        .orElseThrow(InvalidMfaChallengeException::new);
        var user = users.findLockedById(userId).orElseThrow(InvalidMfaChallengeException::new);
        var challenge =
                challenges
                        .findLockedByTokenHash(tokenHash)
                        .orElseThrow(InvalidMfaChallengeException::new);
        if (!challenge.isOpen(now())) {
            throw new InvalidMfaChallengeException();
        }
        return new LockedChallenge(user, challenge);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record LockedChallenge(User user, MfaChallenge challenge) {}
}
