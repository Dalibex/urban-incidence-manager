package es.uma.urbanpulse.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private Boolean enabled = Boolean.TRUE;

    /**
     * The account cannot sign in until its password changes (#165): the administrator who creates
     * it must not keep knowing it.
     */
    @Column(name = "password_change_required", nullable = false)
    private Boolean passwordChangeRequired = Boolean.FALSE;

    /**
     * The TOTP secret of the second factor (#57), encrypted by {@code MfaSecretCipher}; {@code
     * null} while the account has not enrolled. Never returned once the enrolment is confirmed.
     */
    @Column(name = "mfa_secret")
    private String mfaSecret;

    /** The time step of the last accepted code, so the same code is never accepted twice. */
    @Column(name = "mfa_last_time_step")
    private Long mfaLastTimeStep;

    /**
     * The channels of the citizen's notifications (#373, ADR-0031), as the database defaults them:
     * Hibernate writes every column, so a user created here would otherwise skip those defaults.
     * Push starts off because it needs the phone's permission and a registered device.
     */
    @Column(name = "notify_by_push", nullable = false)
    private boolean notifyByPush = false;

    @Column(name = "notify_by_email", nullable = false)
    private boolean notifyByEmail = true;
}
