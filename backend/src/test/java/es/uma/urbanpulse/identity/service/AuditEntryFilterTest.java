package es.uma.urbanpulse.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.uma.urbanpulse.identity.entity.AuditAction;
import es.uma.urbanpulse.shared.web.MalformedRequestException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuditEntryFilterTest {

    private static final Instant NOV_1 = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant NOV_2 = Instant.parse("2026-11-02T00:00:00Z");

    @Test
    void acceptsARangeWhoseStartIsBeforeItsEnd() {
        var filter = new AuditEntryFilter(NOV_1, NOV_2, null, Set.of());

        assertThat(filter.from()).isEqualTo(NOV_1);
        assertThat(filter.to()).isEqualTo(NOV_2);
    }

    @Test
    void acceptsAnOpenRange() {
        assertThat(new AuditEntryFilter(NOV_1, null, null, Set.of()).to()).isNull();
        assertThat(new AuditEntryFilter(null, NOV_2, null, Set.of()).from()).isNull();
    }

    // The range excludes `to`, so equal ends would select nothing: a mistake, not a filter.
    @Test
    void rejectsARangeWhoseStartIsItsEnd() {
        assertThatThrownBy(() -> new AuditEntryFilter(NOV_1, NOV_1, null, Set.of()))
                .isInstanceOf(MalformedRequestException.class);
    }

    @Test
    void rejectsARangeWhoseStartIsAfterItsEnd() {
        assertThatThrownBy(() -> new AuditEntryFilter(NOV_2, NOV_1, null, Set.of()))
                .isInstanceOf(MalformedRequestException.class);
    }

    @Test
    void keepsACopyOfTheActions() {
        var actions = new HashSet<>(Set.of(AuditAction.CREATE_USER));
        var filter = new AuditEntryFilter(null, null, null, actions);

        actions.add(AuditAction.CHANGE_ROLE);

        assertThat(filter.actions()).containsExactly(AuditAction.CREATE_USER);
    }
}
