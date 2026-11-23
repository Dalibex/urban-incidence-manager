package es.uma.urbanpulse.geospatial.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The answers of the address search by text (#179), so a repeated search does not call Nominatim
 * again, as its usage policy asks. Bounded in size and in time; only answers are kept, never a
 * failure.
 *
 * <p>It lives in this process, like the rate limits (one backend replica, ADR-0011), and a restart
 * empties it. A shared cache is Redis, which ADR-0022 adds only from measurements.
 */
class PlaceSearchCache {

    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Answer> entries;

    PlaceSearchCache(Duration ttl, int maxEntries, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
        // In access order, so the eldest entry is the least recently used.
        this.entries =
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Answer> eldest) {
                        return size() > maxEntries;
                    }
                };
    }

    synchronized Optional<List<Place>> get(String query) {
        var key = key(query);
        var answer = entries.get(key);
        if (answer == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(answer.expires())) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(answer.places());
    }

    synchronized void put(String query, List<Place> places) {
        entries.put(key(query), new Answer(List.copyOf(places), clock.instant().plus(ttl)));
    }

    // "Calle  Larios" and "calle larios" are the same search for Nominatim. Accents are kept:
    // they can tell two streets apart.
    static String key(String query) {
        // (?U) makes \s match U+00A0 too, which Texts.strip already takes as white space.
        return query.strip().replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private record Answer(List<Place> places, Instant expires) {}
}
