package uk.valuationloop.lead;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.valuationloop.lead.LeadState.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

class LeadTransitionsTest {

    static Stream<Arguments> legal() {
        return Stream.of(
                Arguments.of(CREATED, new LeadEvent.Deliver(), DELIVERING),
                Arguments.of(DELIVERING, new LeadEvent.DeliverySucceeded(), DELIVERED),
                Arguments.of(DELIVERING, new LeadEvent.DeliveryFailed(false), DELIVERING),
                Arguments.of(DELIVERING, new LeadEvent.DeliveryFailed(true), DELIVERY_FAILED),
                Arguments.of(DELIVERED, new LeadEvent.Replied(), REPLIED),
                Arguments.of(DELIVERED, new LeadEvent.DeadlinePassed(), EXPIRED),
                Arguments.of(EXPIRED, new LeadEvent.Replied(), REPLIED),
                Arguments.of(REPLIED, new LeadEvent.EvidenceAdded(), EVIDENCED),
                Arguments.of(REPLIED, new LeadEvent.VisitBooked(), VISIT_BOOKED),
                Arguments.of(EVIDENCED, new LeadEvent.VisitBooked(), VISIT_BOOKED));
    }

    @ParameterizedTest
    @MethodSource("legal")
    void legalTransitions(LeadState from, LeadEvent event, LeadState expected) {
        assertThat(LeadTransitions.next(from, event)).isEqualTo(expected);
    }

    static Stream<Arguments> illegal() {
        return Stream.of(
                Arguments.of(CREATED, new LeadEvent.Replied()),              // cannot reply to an undelivered lead
                Arguments.of(DELIVERED, new LeadEvent.Deliver()),            // no double delivery
                Arguments.of(REPLIED, new LeadEvent.Replied()),              // second reply is a 409 at the API, never a transition
                Arguments.of(REPLIED, new LeadEvent.DeadlinePassed()),       // a replied lead cannot expire
                Arguments.of(DELIVERY_FAILED, new LeadEvent.Replied()),      // the agent never got it
                Arguments.of(EXPIRED, new LeadEvent.EvidenceAdded()),        // evidence needs a reply first
                Arguments.of(VISIT_BOOKED, new LeadEvent.VisitBooked()));    // idempotency is the caller's job
    }

    @ParameterizedTest
    @MethodSource("illegal")
    void illegalTransitionsThrow(LeadState from, LeadEvent event) {
        assertThatThrownBy(() -> LeadTransitions.next(from, event))
                .isInstanceOf(LeadTransitions.IllegalTransition.class)
                .hasMessageContaining(from.name());
    }

    @Test
    void terminalForDeliveryMeansTheDeliveryWorkerIsDone() {
        assertThat(LeadTransitions.isTerminalForDelivery(DELIVERING)).isFalse();
        assertThat(LeadTransitions.isTerminalForDelivery(DELIVERED)).isFalse();
        assertThat(LeadTransitions.isTerminalForDelivery(REPLIED)).isTrue();
        assertThat(LeadTransitions.isTerminalForDelivery(DELIVERY_FAILED)).isTrue();
    }
}
