package es.uma.urbanpulse.notifications.service;

import es.uma.urbanpulse.notifications.repository.PushTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes the push tokens no device registered again for 60 days (ADR-0031): the app registers its
 * token at every start, so such a device is gone or no longer signed in.
 *
 * <p>Runs inside the backend once a day. There is a single replica (ADR-0011), and two runs at once
 * would only delete the same rows.
 */
@Service
public class PushTokenCleanup {

    private static final Logger log = LoggerFactory.getLogger(PushTokenCleanup.class);

    static final Duration RETENTION = Duration.ofDays(60);

    private final PushTokenRepository pushTokens;
    private final Clock clock;

    public PushTokenCleanup(PushTokenRepository pushTokens, Clock clock) {
        this.pushTokens = pushTokens;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @Transactional
    public void deleteStale() {
        var cutoff = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minus(RETENTION);
        var deleted = pushTokens.deleteRegisteredBefore(cutoff);
        log.info("Deleted {} push tokens last registered before {}", deleted, cutoff);
    }
}
