package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.identity.dto.CreatePasswordChangeRequest;
import es.uma.urbanpulse.identity.entity.AuditAction;
import es.uma.urbanpulse.identity.repository.RefreshTokenRepository;
import es.uma.urbanpulse.identity.repository.UserRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Changes a password with the current one (#165). It is public: an account that must change its
 * password has no tokens yet, so the current password is the credential.
 */
@Service
public class PasswordChangeService {

    private static final String PASSWORD_CHANGE_REQUIRED = "passwordChangeRequired";

    private final CredentialCheck credentials;
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    PasswordChangeService(
            CredentialCheck credentials,
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder,
            AuditService audit,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.credentials = credentials;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Checks the current password like sign-in, under the same rate limit ({@link
     * CredentialCheck}), then replaces it, clears the obligation to change it, revokes every
     * refresh token of the user and records {@code CHANGE_PASSWORD}, in one transaction: if any of
     * them fails, the password stays as it was.
     *
     * <p>The two bcrypt steps (checking the current password and hashing the new one) run before
     * the transaction, so it does not hold a database connection for about 200 ms.
     */
    public void changePassword(CreatePasswordChangeRequest request, String clientAddress) {
        // Before the credentials: comparing two strings the client sent reveals nothing about the
        // account, and a request that cannot succeed should not spend an attempt.
        if (request.newPassword().equals(request.currentPassword())) {
            throw new SamePasswordException();
        }
        var email = EmailAddresses.normalize(request.email());
        var user = credentials.verify(email, request.currentPassword(), clientAddress);
        var newHash = passwordEncoder.encode(request.newPassword());
        transactions.executeWithoutResult(
                status -> replacePassword(user.getId(), user.getPasswordHash(), newHash));
    }

    private void replacePassword(UUID userId, String verifiedHash, String newHash) {
        // Locked and read again: of two changes at once with the same current password, the second
        // waits for the first and then finds that the password it checked is no longer the one
        // stored, so it gets the same 401 as a wrong password.
        var user = users.findLockedById(userId).orElseThrow(InvalidCredentialsException::new);
        if (!user.getPasswordHash().equals(verifiedHash)) {
            throw new InvalidCredentialsException();
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new AccountDisabledException();
        }
        var wasRequired = Boolean.TRUE.equals(user.getPasswordChangeRequired());
        user.setPasswordHash(newHash);
        user.setPasswordChangeRequired(false);
        refreshTokens.revokeAllActive(
                userId, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        // The account is its own actor: it is the only one who knows the password.
        audit.record(
                userId,
                AuditAction.CHANGE_PASSWORD,
                AuditTarget.user(userId),
                Map.of(PASSWORD_CHANGE_REQUIRED, wasRequired),
                Map.of(PASSWORD_CHANGE_REQUIRED, false));
    }
}
