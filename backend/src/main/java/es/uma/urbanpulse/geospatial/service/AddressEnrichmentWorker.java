package es.uma.urbanpulse.geospatial.service;

import es.uma.urbanpulse.geospatial.repository.LocationAddressRepository;
import es.uma.urbanpulse.geospatial.repository.PendingAddress;
import es.uma.urbanpulse.incidents.entity.AddressStatus;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Resolves the {@code PENDING} addresses from their position (#375), with the mechanism of the
 * weather worker (ADR-0018). An address that waits survives a restart, because it is a row; the
 * next run takes it.
 *
 * <p>Retries are bounded by time: an address whose call fails stays {@code PENDING} for the next
 * run until {@code urbanpulse.address.max-delay}. Two failures in a row mean that the source itself
 * is failing, and the rest of the batch waits. Calls are at least {@code
 * urbanpulse.address.request-spacing} apart, as the Nominatim usage policy asks.
 */
@Service
public class AddressEnrichmentWorker {

    private static final Logger log = LoggerFactory.getLogger(AddressEnrichmentWorker.class);

    /** Two failures in a row mean that the source is failing, not one position. */
    static final int FAILURES_IN_A_ROW_THAT_STOP_A_RUN = 2;

    private final AddressEnrichmentProperties properties;
    private final AddressProvider provider;
    private final LocationAddressRepository locations;
    private final AddressWriter writer;
    private final Clock clock;

    public AddressEnrichmentWorker(
            AddressEnrichmentProperties properties,
            AddressProvider provider,
            LocationAddressRepository locations,
            AddressWriter writer,
            Clock clock) {
        this.properties = properties;
        this.provider = provider;
        this.locations = locations;
        this.writer = writer;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${urbanpulse.address.interval}",
            fixedDelayString = "${urbanpulse.address.interval}")
    void scheduledRun() {
        if (properties.enabled()) {
            resolvePending();
        }
    }

    /** One run of the worker. Public for the tests, which call it instead of waiting. */
    public void resolvePending() {
        var cutoff = clock.instant().minus(properties.maxDelay());
        writer.expireReportedBefore(cutoff);

        var failuresInARow = 0;
        var first = true;
        for (var pending : locations.findOldestPending(Limit.of(properties.batchSize()))) {
            if (!first && !waitBetweenCalls()) {
                return;
            }
            first = false;
            if (resolveAndTellIfTheSourceAnswered(pending)) {
                failuresInARow = 0;
                continue;
            }
            failuresInARow++;
            if (failuresInARow == FAILURES_IN_A_ROW_THAT_STOP_A_RUN) {
                log.warn(
                        "Address enrichment stopped this run: {} failures in a row",
                        failuresInARow);
                return;
            }
        }
    }

    /**
     * @return {@code false} if the thread was interrupted (the backend is stopping), so the run
     *     ends
     */
    private boolean waitBetweenCalls() {
        Duration spacing = properties.requestSpacing();
        if (spacing.isZero()) {
            return true;
        }
        try {
            Thread.sleep(spacing);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * @return {@code false} only if the source did not answer, so the address waits for the next
     *     run
     */
    private boolean resolveAndTellIfTheSourceAnswered(PendingAddress pending) {
        AddressLookup lookup;
        try {
            lookup = provider.lookUp(pending.latitude(), pending.longitude());
        } catch (AddressSourceUnavailableException e) {
            // The location id is enough to find the incident; the position of a report is not
            // written to the log.
            log.warn("No address for location {}: {}", pending.locationId(), e.getMessage());
            return false;
        } catch (AddressAnswerRejectedException e) {
            log.warn("Address refused for location {}: {}", pending.locationId(), e.getMessage());
            end(pending, AddressStatus.UNAVAILABLE);
            return true;
        }

        switch (lookup) {
            case AddressLookup.Found found -> store(pending, found.address());
            case AddressLookup.OutsideArea _ -> end(pending, AddressStatus.OUTSIDE_AREA);
            case AddressLookup.NoStreet _ -> end(pending, AddressStatus.UNAVAILABLE);
        }
        return true;
    }

    private void store(PendingAddress pending, String address) {
        writer.resolve(pending.locationId(), address, provider.source(), clock.instant());
    }

    private void end(PendingAddress pending, AddressStatus status) {
        writer.end(pending.locationId(), status);
    }
}
