package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.identity.dto.CreateRegistrationRequest;
import es.uma.urbanpulse.identity.entity.AuditAction;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.identity.repository.UserRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first administrator of a deployment (#126). Sign-up only creates citizens and staff
 * accounts are created by an administrator (#40), so a clean database gets its first one from the
 * one-off process {@code identity.controller.FirstAdministratorCommand}.
 */
@Service
public class FirstAdministratorService {

    /**
     * The {@code origin} of its audit entry. No administrator exists to be the actor, so the new
     * account is its own actor (docs/security.md), and this value tells the entry apart.
     */
    static final String AUDIT_ORIGIN = "BOOTSTRAP";

    /** What a run did. */
    public enum Outcome {
        CREATED,
        /** An enabled administrator already existed, so nothing changed. */
        ALREADY_PRESENT
    }

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final Validator validator;

    FirstAdministratorService(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            AuditService audit,
            Validator validator) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.validator = validator;
    }

    /**
     * Creates an enabled administrator unless an enabled one already exists, so running the process
     * again is safe. With an administrator, the values are not even checked: the password can be
     * removed from the environment once the account exists, and the run still succeeds.
     *
     * <p>Otherwise the values follow the rules of sign-up, because they are checked as a {@link
     * CreateRegistrationRequest}.
     *
     * <p>Transactional as a whole, bcrypt included, unlike sign-up: a one-off run holds a single
     * connection for a moment, and the check, the account and its audit entry commit together. Two
     * runs at the same time with different emails could still create two administrators; the
     * process is run by hand, once.
     *
     * @throws FirstAdministratorRejectedException if a value breaks a rule of sign-up, or the email
     *     belongs to an account that is not an enabled administrator
     */
    @Transactional
    public Outcome createIfAbsent(String name, String email, String password) {
        if (users.existsByRoleAndEnabledTrue(Role.ADMINISTRATOR)) {
            return Outcome.ALREADY_PRESENT;
        }
        var request = new CreateRegistrationRequest(name, email, password);
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new FirstAdministratorRejectedException(describe(violations));
        }
        var normalizedEmail = EmailAddresses.normalize(email);
        if (users.existsByEmail(normalizedEmail)) {
            // Promoting it would hand the role to whoever signed up with that email first.
            throw new FirstAdministratorRejectedException(
                    "an account with that email already exists and is not an enabled"
                            + " administrator");
        }

        var user = new User();
        user.setName(name.strip());
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(Role.ADMINISTRATOR);
        user.setEnabled(true);
        var saved = users.save(user);

        audit.record(
                saved.getId(),
                AuditAction.CREATE_USER,
                AuditTarget.user(saved.getId()),
                Map.of(),
                Map.of("role", Role.ADMINISTRATOR, "origin", AUDIT_ORIGIN));
        return Outcome.CREATED;
    }

    /** Field and rule only: never the rejected value, which may be the password. */
    private static String describe(Set<ConstraintViolation<CreateRegistrationRequest>> violations) {
        return violations.stream()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining("; "));
    }
}
