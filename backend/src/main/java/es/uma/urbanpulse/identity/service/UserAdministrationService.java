package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.identity.dto.CreateUserRequest;
import es.uma.urbanpulse.identity.dto.UpdateUserRequest;
import es.uma.urbanpulse.identity.dto.UserResponse;
import es.uma.urbanpulse.identity.entity.AuditAction;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.identity.repository.UserRepository;
import es.uma.urbanpulse.shared.error.FieldError;
import es.uma.urbanpulse.shared.web.PageResponse;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * User management by administrators (#40): list, read, create staff accounts and change them. The
 * controller lets only {@code ADMINISTRATOR} in; every change records its audit entry in the same
 * transaction ({@code docs/security.md}, "Audit trail").
 */
@Service
public class UserAdministrationService {

    // Audit details: identifiers and domain values only, never a name, an email or a password.
    private static final String ROLE = "role";
    private static final String ENABLED = "enabled";
    private static final String PASSWORD_CHANGE_REQUIRED = "passwordChangeRequired";
    private static final String NAME = "name";
    private static final String CHANGED = "changed";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessions;
    private final AuditService audit;
    private final Validator validator;
    private final TransactionTemplate transactions;

    UserAdministrationService(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            SessionService sessions,
            AuditService audit,
            Validator validator,
            PlatformTransactionManager transactionManager) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.audit = audit;
        this.validator = validator;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(UserFilter filter, Pageable page) {
        return PageResponse.of(users.findAll(filter.specification(), page), UserResponse::from);
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID userId) {
        return users.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    /**
     * Creates an enabled staff account that must change its password at the first sign-in (#165),
     * and records {@code CREATE_USER}.
     *
     * <p>bcrypt runs before the transaction, so it does not hold a database connection for about
     * 200 ms. The email is checked first so a duplicate does not spend that time, and the unique
     * constraint settles two creations with the same email at once.
     *
     * @throws InvalidUserChangeException if a field is not valid or the role is {@code CITIZEN}:
     *     citizens sign up
     * @throws EmailAlreadyExistsException if an account already has the email
     */
    public UserResponse create(UUID administratorId, CreateUserRequest request) {
        var errors = constraintErrors(request);
        if (request.role() == Role.CITIZEN) {
            errors.add(
                    new FieldError(ROLE, "must be a staff role: citizens sign up by themselves"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidUserChangeException(errors);
        }
        var email = EmailAddresses.normalize(request.email());
        if (users.existsByEmail(email)) {
            throw new EmailAlreadyExistsException();
        }
        var user = new User();
        user.setName(request.name().strip());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(request.role());
        user.setEnabled(true);
        user.setPasswordChangeRequired(true);
        try {
            return transactions.execute(status -> insert(administratorId, user));
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyExistsException();
        }
    }

    private UserResponse insert(UUID administratorId, User user) {
        // Flushed here, so a duplicate email fails before the audit entry is written.
        var saved = users.saveAndFlush(user);
        audit.record(
                administratorId,
                AuditAction.CREATE_USER,
                AuditTarget.user(saved.getId()),
                Map.of(),
                Map.of(
                        ROLE, saved.getRole(),
                        ENABLED, true,
                        PASSWORD_CHANGE_REQUIRED, true));
        return UserResponse.from(saved);
    }

    /**
     * Applies the properties present in the request that differ from the stored ones, with one
     * audit entry per kind of change; a request that changes nothing records nothing. Disabling
     * revokes every refresh token of the user, so the account cannot refresh again.
     *
     * <p>The user row is locked first: of two changes at once, the second waits and its {@code
     * before} is the result of the first (#63, T16).
     *
     * @throws UserNotFoundException if no user has the id
     * @throws InvalidUserChangeException if a field is not valid, administrators change their own
     *     role or disable themselves, or the role changes from or to {@code CITIZEN}; nothing
     *     changes then
     */
    @Transactional
    public UserResponse update(UUID administratorId, UUID userId, UpdateUserRequest request) {
        var user =
                users.findLockedById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        var self = user.getId().equals(administratorId);

        var newName = request.name() == null ? null : request.name().strip();
        var renames = newName != null && !newName.equals(user.getName());
        var changesRole = request.role() != null && request.role() != user.getRole();
        var wasEnabled = Boolean.TRUE.equals(user.getEnabled());
        var changesEnabled = request.enabled() != null && request.enabled() != wasEnabled;

        var errors = constraintErrors(request);
        // strip() and isBlank() both use Character.isWhitespace, so a name of Unicode spaces is
        // refused here instead of being stored empty.
        if (newName != null && newName.isEmpty()) {
            errors.add(new FieldError(NAME, "must not be blank"));
        }
        if (changesRole && self) {
            errors.add(new FieldError(ROLE, "you cannot change your own role"));
        } else if (changesRole
                && (request.role() == Role.CITIZEN || user.getRole() == Role.CITIZEN)) {
            errors.add(
                    new FieldError(
                            ROLE,
                            "cannot change from or to CITIZEN: citizens sign up by themselves"));
        }
        if (changesEnabled && self && wasEnabled) {
            errors.add(new FieldError(ENABLED, "you cannot disable your own account"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidUserChangeException(errors);
        }

        var target = AuditTarget.user(userId);
        if (renames) {
            user.setName(newName);
            // Which field changed, never the name itself: the trail cannot be erased.
            audit.record(
                    administratorId,
                    AuditAction.UPDATE_USER,
                    target,
                    Map.of(),
                    Map.of(NAME, CHANGED));
        }
        if (changesRole) {
            var oldRole = user.getRole();
            user.setRole(request.role());
            audit.record(
                    administratorId,
                    AuditAction.CHANGE_ROLE,
                    target,
                    Map.of(ROLE, oldRole),
                    Map.of(ROLE, request.role()));
        }
        if (changesEnabled) {
            user.setEnabled(request.enabled());
            if (wasEnabled) {
                sessions.revokeAllSessions(userId);
            }
            audit.record(
                    administratorId,
                    wasEnabled ? AuditAction.DISABLE_USER : AuditAction.ENABLE_USER,
                    target,
                    Map.of(ENABLED, wasEnabled),
                    Map.of(ENABLED, !wasEnabled));
        }
        return UserResponse.from(user);
    }

    /**
     * The Bean Validation errors of a body, ordered by field. The controller has no {@code @Valid}
     * (it would answer {@code 422} before the role check), so they are checked here, after it, and
     * joined with the rules of the service in one {@code 422}.
     */
    private ArrayList<FieldError> constraintErrors(Object body) {
        return validator.validate(body).stream()
                .map(
                        violation ->
                                new FieldError(
                                        violation.getPropertyPath().toString(),
                                        violation.getMessage()))
                .sorted(Comparator.comparing(FieldError::field))
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
