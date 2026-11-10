package es.uma.urbanpulse.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.uma.urbanpulse.identity.dto.AuditEntryResponse;
import es.uma.urbanpulse.identity.entity.AuditAction;
import es.uma.urbanpulse.identity.entity.AuditLog;
import es.uma.urbanpulse.identity.entity.AuditTargetType;
import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.identity.repository.AuditLogRepository;
import es.uma.urbanpulse.identity.repository.UserRepository;
import es.uma.urbanpulse.incidents.repository.IncidentRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * How {@link AuditEntryQueryService} turns stored entries into responses: labels, targets and
 * details. The queries and the filters are covered against the database by the tests of #64.
 */
class AuditEntryQueryServiceTest {

    private static final OffsetDateTime INSTANT =
            OffsetDateTime.of(2026, 11, 2, 9, 58, 31, 0, ZoneOffset.UTC);
    private static final AuditEntryFilter NO_FILTER =
            new AuditEntryFilter(null, null, null, Set.of());

    private final AuditLogRepository auditLogs = mock(AuditLogRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final AuditEntryQueryService service =
            new AuditEntryQueryService(auditLogs, users, incidents, JsonMapper.builder().build());

    private User admin;

    @BeforeEach
    void createActor() {
        admin = user("Lucía Martín");
    }

    @Test
    void returnsTheActorWithTheCurrentNameAndTheInstantInUtc() {
        pageOf(entry(AuditAction.CREATE_USER, AuditTargetType.USER, admin.getId(), null));
        when(users.findAllById(anyCollection())).thenReturn(List.of(admin));

        var response = onlyEntry();

        assertThat(response.actor())
                .isEqualTo(new AuditEntryResponse.Actor(admin.getId(), "Lucía Martín"));
        assertThat(response.instant()).isEqualTo(INSTANT.toInstant());
        assertThat(response.action()).isEqualTo(AuditAction.CREATE_USER);
    }

    @Test
    void labelsAUserTargetWithItsNameAndAnIncidentWithItsTitle() {
        var pedro = user("Pedro Gómez");
        var incidentId = UUID.randomUUID();
        pageOf(
                entry(AuditAction.CHANGE_ROLE, AuditTargetType.USER, pedro.getId(), null),
                entry(AuditAction.CLASSIFY_INCIDENT, AuditTargetType.INCIDENT, incidentId, null));
        when(users.findAllById(anyCollection())).thenReturn(List.of(pedro));
        when(incidents.findTitlesByIdIn(anyCollection()))
                .thenReturn(List.of(title(incidentId, "Broken streetlight")));

        var content = service.list(NO_FILTER, PageRequest.of(0, 20)).content();

        assertThat(content.get(0).target())
                .isEqualTo(
                        new AuditEntryResponse.Target(
                                AuditTargetType.USER, pedro.getId(), "Pedro Gómez"));
        assertThat(content.get(1).target())
                .isEqualTo(
                        new AuditEntryResponse.Target(
                                AuditTargetType.INCIDENT, incidentId, "Broken streetlight"));
    }

    // Unknown data is not invented: the record is gone, so the label says nothing.
    @Test
    void leavesTheLabelEmptyWhenTheRecordNoLongerExists() {
        var missing = UUID.randomUUID();
        pageOf(entry(AuditAction.CLASSIFY_INCIDENT, AuditTargetType.INCIDENT, missing, null));
        when(incidents.findTitlesByIdIn(anyCollection())).thenReturn(List.of());

        assertThat(onlyEntry().target())
                .isEqualTo(new AuditEntryResponse.Target(AuditTargetType.INCIDENT, missing, null));
    }

    @Test
    void hasNoTargetAndQueriesNoLabelWhenTheEntryIsNotAboutARecord() {
        pageOf(entry(AuditAction.CREATE_USER, null, null, null));

        assertThat(onlyEntry().target()).isNull();
        verify(users, never()).findAllById(any());
        verify(incidents, never()).findTitlesByIdIn(any());
    }

    @Test
    void hasNoDetailsWhenTheEntryHasNone() {
        pageOf(entry(AuditAction.CREATE_USER, null, null, null));

        assertThat(onlyEntry().details()).isNull();
    }

    // AuditService records a field without value as null: the response keeps it, not drops it.
    @Test
    void keepsTheBeforeAndAfterValuesIncludingNulls() {
        pageOf(
                entry(
                        AuditAction.CLASSIFY_INCIDENT,
                        null,
                        null,
                        "{\"before\": {\"priority\": null, \"category\": \"Lighting\"},"
                                + " \"after\": {\"priority\": \"HIGH\", \"count\": 2}}"));

        var details = onlyEntry().details();

        // In the order they were recorded, as the screen lists them.
        assertThat(details.before().keySet()).containsExactly("priority", "category");
        assertThat(details.before())
                .containsEntry("priority", null)
                .containsEntry("category", "Lighting");
        assertThat(details.after()).containsEntry("priority", "HIGH").containsEntry("count", 2);
    }

    @Test
    void readsAMissingSideAsEmpty() {
        pageOf(entry(AuditAction.CREATE_USER, null, null, "{\"after\": {\"role\": \"ANALYST\"}}"));

        var details = onlyEntry().details();

        assertThat(details.before()).isEmpty();
        assertThat(details.after()).containsEntry("role", "ANALYST");
    }

    private AuditEntryResponse onlyEntry() {
        var content = service.list(NO_FILTER, PageRequest.of(0, 20)).content();
        assertThat(content).hasSize(1);
        return content.get(0);
    }

    private void pageOf(AuditLog... entries) {
        when(auditLogs.findMatching(any(), any(), any(), anySet(), any()))
                .thenReturn(
                        new PageImpl<>(List.of(entries), PageRequest.of(0, 20), entries.length));
    }

    private AuditLog entry(
            AuditAction action, AuditTargetType targetType, UUID targetId, String details) {
        return new AuditLog(admin, INSTANT, action, targetType, targetId, details);
    }

    private static User user(String name) {
        var user = new User();
        user.setId(UUID.randomUUID());
        user.setName(name);
        return user;
    }

    private static IncidentRepository.Title title(UUID id, String title) {
        return new IncidentRepository.Title() {
            @Override
            public UUID getId() {
                return id;
            }

            @Override
            public String getTitle() {
                return title;
            }
        };
    }
}
