package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.identity.repository.RefreshTokenRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes the refresh tokens that expired longer ago than the retention (#125). It goes by expiry,
 * not by revocation: a revoked token must stay while it would still work, or presenting it again
 * would look like an unknown token instead of a reused one ({@link SessionService#refresh}).
 *
 * <p>Runs inside the backend once a day. There is a single replica (ADR-0011), and two runs at once
 * would only delete the same rows.
 */
@Service
public class RefreshTokenCleanup {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanup.class);

    private final RefreshTokenRepository refreshTokens;
    private final AccountSecurityProperties properties;
    private final Clock clock;

    public RefreshTokenCleanup(
            RefreshTokenRepository refreshTokens,
            AccountSecurityProperties properties,
            Clock clock) {
        this.refreshTokens = refreshTokens;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @Transactional
    public void deleteExpiredTokens() {
        var cutoff =
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                        .minus(properties.refreshTokenRetention());
        var deleted = refreshTokens.deleteExpiredBefore(cutoff);
        log.info("Deleted {} refresh tokens expired before {}", deleted, cutoff);
    }
}
