package es.uma.urbanpulse.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The first step of a sign-in that needs the second factor (#57, ADR-0021): the password was right,
 * and no session starts until a valid code is sent for it. Only the hash of its token is stored.
 */
@Entity
@Table(name = "mfa_challenges")
@Getter
@Setter
@NoArgsConstructor
public class MfaChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    /** The account had no second factor when the challenge was created: the code confirms one. */
    @Column(nullable = false)
    private Boolean enrollment;

    /** The secret of the enrolment in progress, encrypted; only for enrolment challenges. */
    @Column(name = "pending_secret")
    private String pendingSecret;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "spent_at")
    private OffsetDateTime spentAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Whether the challenge can still be used: not spent and not expired at {@code now}. */
    public boolean isOpen(OffsetDateTime now) {
        return spentAt == null && expiresAt.isAfter(now);
    }
}
