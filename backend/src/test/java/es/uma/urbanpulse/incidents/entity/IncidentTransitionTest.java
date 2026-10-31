package es.uma.urbanpulse.incidents.entity;

import static es.uma.urbanpulse.incidents.entity.IncidentStatus.ASSIGNED;
import static es.uma.urbanpulse.incidents.entity.IncidentStatus.CLOSED;
import static es.uma.urbanpulse.incidents.entity.IncidentStatus.IN_PROGRESS;
import static es.uma.urbanpulse.incidents.entity.IncidentStatus.REJECTED;
import static es.uma.urbanpulse.incidents.entity.IncidentStatus.REOPENED;
import static es.uma.urbanpulse.incidents.entity.IncidentStatus.REPORTED;
import static es.uma.urbanpulse.incidents.entity.IncidentStatus.RESOLVED;
import static es.uma.urbanpulse.incidents.entity.IncidentStatus.VALIDATED;
import static es.uma.urbanpulse.incidents.entity.IncidentTransition.Performer.ASSIGNED_TECHNICIAN;
import static es.uma.urbanpulse.incidents.entity.IncidentTransition.Performer.OPERATOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.incidents.entity.IncidentTransition.Performer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The transition table against ADR-0020: every origin/destination pair of the eight statuses, and
 * every transition tried from every status by every kind of user. The expected table is written out
 * here again, from the ADR, so a change in {@link IncidentTransition} that the ADR does not have
 * makes these tests fail (hypothesis of ADR-0020: 100 % of the pairs, 0 forbidden accepted).
 */
class IncidentTransitionTest {

    private record Row(
            IncidentTransition transition, IncidentStatus from, IncidentStatus to, Performer who) {}

    // ADR-0020, "Transition table": the 10 allowed pairs. The other 54 are forbidden.
    private static final List<Row> ADR_0020 =
            List.of(
                    new Row(IncidentTransition.INFORMATION_REQUEST, REPORTED, REPORTED, OPERATOR),
                    new Row(IncidentTransition.VALIDATION, REPORTED, VALIDATED, OPERATOR),
                    new Row(IncidentTransition.REJECTION, REPORTED, REJECTED, OPERATOR),
                    new Row(IncidentTransition.ASSIGNMENT, VALIDATED, ASSIGNED, OPERATOR),
                    new Row(IncidentTransition.ASSIGNMENT, REOPENED, ASSIGNED, OPERATOR),
                    new Row(IncidentTransition.ASSIGNMENT, ASSIGNED, ASSIGNED, OPERATOR),
                    new Row(
                            IncidentTransition.ACCEPTANCE,
                            ASSIGNED,
                            IN_PROGRESS,
                            ASSIGNED_TECHNICIAN),
                    new Row(
                            IncidentTransition.RESOLUTION,
                            IN_PROGRESS,
                            RESOLVED,
                            ASSIGNED_TECHNICIAN),
                    new Row(IncidentTransition.CLOSURE, RESOLVED, CLOSED, OPERATOR),
                    new Row(IncidentTransition.REOPENING, RESOLVED, REOPENED, OPERATOR));

    /** The kinds of user that can try a transition, and whether each one counts as a performer. */
    private enum Actor {
        CITIZEN(fixture -> fixture.citizen, null),
        MUNICIPAL_OPERATOR(fixture -> fixture.operator, OPERATOR),
        ADMINISTRATOR(fixture -> fixture.administrator, OPERATOR),
        ASSIGNED_TECHNICIAN(fixture -> fixture.technician, Performer.ASSIGNED_TECHNICIAN),
        OTHER_TECHNICIAN(fixture -> fixture.otherTechnician, null),
        ANALYST(fixture -> fixture.analyst, null);

        private final Function<IncidentFixture, User> user;
        private final Performer counts;

        Actor(Function<IncidentFixture, User> user, Performer counts) {
            this.user = user;
            this.counts = counts;
        }
    }

    static Stream<Arguments> everyPair() {
        var pairs = new ArrayList<Arguments>();
        for (var from : IncidentStatus.values()) {
            for (var to : IncidentStatus.values()) {
                pairs.add(Arguments.of(from, to));
            }
        }
        return pairs.stream();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    void everyPairIsAllowedOnlyByTheTransitionOfTheAdr(IncidentStatus from, IncidentStatus to) {
        var expected =
                ADR_0020.stream()
                        .filter(row -> row.from() == from && row.to() == to)
                        .map(row -> row.transition() + " by " + row.who())
                        .toList();
        var actual =
                Arrays.stream(IncidentTransition.values())
                        .filter(
                                transition ->
                                        transition.startsFrom(from) && transition.target() == to)
                        .map(transition -> transition + " by " + transition.performer())
                        .toList();

        assertThat(actual).isEqualTo(expected);
    }

    static Stream<Arguments> everyTransitionFromEveryStatusByEveryActor() {
        var cases = new ArrayList<Arguments>();
        for (var from : IncidentStatus.values()) {
            for (var transition : IncidentTransition.values()) {
                for (var actor : Actor.values()) {
                    cases.add(Arguments.of(from, transition, actor));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{1} from {0} by {2}")
    @MethodSource("everyTransitionFromEveryStatusByEveryActor")
    void onlyTheAllowedTransitionsChangeTheStatus(
            IncidentStatus from, IncidentTransition transition, Actor actor) {
        var fixture = new IncidentFixture();
        var incident = fixture.in(from);
        var user = actor.user.apply(fixture);
        var details = fixture.completeDetails(transition);
        var historyBefore = incident.getStatusChanges().size();
        var updatedBefore = incident.getUpdatedAt();

        boolean allowed =
                ADR_0020.stream()
                        .anyMatch(
                                row ->
                                        row.transition() == transition
                                                && row.from() == from
                                                && row.who() == actor.counts);

        if (allowed) {
            var change = incident.changeStatus(transition, user, details, IncidentFixture.LATER);

            assertThat(incident.getStatus()).isEqualTo(transition.target());
            assertThat(incident.getStatusChanges())
                    .hasSize(historyBefore + 1)
                    .last()
                    .isSameAs(change);
        } else {
            assertThatThrownBy(
                            () ->
                                    incident.changeStatus(
                                            transition, user, details, IncidentFixture.LATER))
                    .isInstanceOfSatisfying(
                            InvalidTransitionException.class,
                            ex -> {
                                assertThat(ex.status().value()).isEqualTo(409);
                                assertThat(ex.code()).contains("INVALID_TRANSITION");
                            });
            assertThat(incident.getStatus()).isEqualTo(from);
            assertThat(incident.getStatusChanges()).hasSize(historyBefore);
            assertThat(incident.getUpdatedAt()).isEqualTo(updatedBefore);
        }
    }

    @ParameterizedTest
    @MethodSource("everyTransition")
    void onlyTheInformationRequestAndTheRejectionShowTheirReasonToTheCitizen(
            IncidentTransition transition) {
        assertThat(transition.citizenSeesReason())
                .isEqualTo(
                        transition == IncidentTransition.INFORMATION_REQUEST
                                || transition == IncidentTransition.REJECTION);
    }

    static Stream<IncidentTransition> everyTransition() {
        return Arrays.stream(IncidentTransition.values());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    void aHistoryEntryTellsWhichTransitionMadeIt(IncidentStatus from, IncidentStatus to) {
        var expected =
                ADR_0020.stream()
                        .filter(row -> row.from() == from && row.to() == to)
                        .map(Row::transition)
                        .findFirst();

        assertThat(IncidentTransition.between(from, to)).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("everyStatus")
    void theFirstEntryWasMadeByNoTransition(IncidentStatus to) {
        assertThat(IncidentTransition.between(null, to)).isEmpty();
    }

    static Stream<IncidentStatus> everyStatus() {
        return Arrays.stream(IncidentStatus.values());
    }
}
