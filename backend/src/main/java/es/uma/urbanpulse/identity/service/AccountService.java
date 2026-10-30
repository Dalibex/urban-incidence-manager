package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.identity.dto.CreateRegistrationRequest;
import es.uma.urbanpulse.identity.dto.UserResponse;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.identity.repository.UserRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Citizen sign-up and the profile of the signed-in user. */
@Service
public class AccountService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AttemptLimiter signUpLimiter;

    public AccountService(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            AccountSecurityProperties properties,
            Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.signUpLimiter = new AttemptLimiter(properties.signUpAttempts(), clock);
    }

    /**
     * Creates a citizen account. Every attempt counts towards the limit of the client address, so
     * nobody can probe many emails for {@code EMAIL_ALREADY_EXISTS}.
     *
     * <p>Not transactional on purpose, like sign-in: bcrypt would hold a database connection. The
     * single insert is atomic by itself, and the unique constraint settles a race.
     */
    public UserResponse signUp(CreateRegistrationRequest request, String clientAddress) {
        signUpLimiter.acquire(clientAddress);

        var email = EmailAddresses.normalize(request.email());
        if (users.existsByEmail(email)) {
            throw new EmailAlreadyExistsException();
        }
        var user = new User();
        user.setName(request.name().strip());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(Role.CITIZEN);
        user.setEnabled(true);
        try {
            return UserResponse.from(users.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            // Two sign-ups with the same email at once: the unique constraint stops the second.
            throw new EmailAlreadyExistsException();
        }
    }

    /** Empty when the user of a valid token no longer exists. */
    @Transactional(readOnly = true)
    public Optional<UserResponse> profile(UUID userId) {
        return users.findById(userId).map(UserResponse::from);
    }
}
