package uk.valuationloop.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import uk.valuationloop.support.EventSchemas;

/**
 * The event contract: one example envelope per event type, serialised by the app's own ObjectMapper
 * (@JsonTest starts only the JSON part of Spring, no database, no Kafka), must match its schema file.
 */
@JsonTest
class EventSchemasTest {

    private static final String REQUEST = UuidV7.prefixed("vr");
    private static final String LEAD = UuidV7.prefixed("ld");
    private static final String AGENT = "ag_fictional_01";

    @Autowired ObjectMapper json;

    /** eventType, leadId, agentId, payload. Request-level events have no lead and no agent. */
    static Stream<Arguments> examples() {
        return Stream.of(
                Arguments.of(EventTypes.VALUATION_REQUESTED, null, null, Map.of(
                        "postcode", "SW11 1AA", "outcode", "SW11", "propertyType", "FLAT", "bedrooms", 2)),
                Arguments.of(EventTypes.AGENTS_MATCHED, null, null, Map.of(
                        "agentIds", List.of("ag_fictional_01", "ag_fictional_02"), "eligibilityRule", "slot-holders-for-outcode")),
                Arguments.of(EventTypes.REQUEST_CLOSED, null, null, Map.of("reason", "SELLER_SELECTED")),
                Arguments.of(EventTypes.DELIVER_LEAD, LEAD, AGENT, Map.of("adapter", "flaky")),
                Arguments.of(EventTypes.LEAD_DELIVERED, LEAD, AGENT, Map.of("adapter", "ok", "attempt", 1)),
                Arguments.of(EventTypes.LEAD_DELIVERY_FAILED, LEAD, AGENT, Map.of(
                        "adapter", "flaky", "attempts", 3, "reason", "simulated CRM timeout")),
                Arguments.of(EventTypes.LEAD_REPLIED, LEAD, AGENT, withNulls(Map.of(
                        "low", 500000, "high", 540000, "late", false, "repliedAt", "2026-10-16T10:30:00Z"), "message", "deliveredAt")),
                Arguments.of(EventTypes.LEAD_REPLIED, LEAD, AGENT, Map.of(
                        "low", 500000, "high", 540000, "message", "Happy to visit on Tuesday", "late", true,
                        "deliveredAt", "2026-10-16T09:00:00Z", "repliedAt", "2026-10-17T11:00:00Z")),
                Arguments.of(EventTypes.EVIDENCE_ATTACHED, LEAD, AGENT, Map.of(
                        "packId", UuidV7.prefixed("ep"), "comparablesCount", 8,
                        "comparablesRange", Map.of("low", 485000, "mid", 515000, "high", 560000))),
                Arguments.of(EventTypes.LEAD_EXPIRED, LEAD, AGENT, Map.of("replyDueAt", "2026-10-17T09:00:00Z")),
                Arguments.of(EventTypes.SELLER_ENGAGED, LEAD, AGENT, Map.of("action", "VISIT_REQUESTED")),
                Arguments.of(EventTypes.SELLER_SELECTED, LEAD, AGENT, Map.of()),
                Arguments.of(EventTypes.VISIT_BOOKED, LEAD, AGENT, withNulls(Map.of(), "visitAt")),
                Arguments.of(EventTypes.INSTRUCTION_RECORDED, LEAD, AGENT, Map.of(
                        "leadRef", LEAD, "source", "crm-webhook", "recordedAt", "2026-10-20T14:30:00Z")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    void exampleEnvelopeMatchesItsSchema(String eventType, String leadId, String agentId, Map<String, Object> payload)
            throws Exception {
        String wire = json.writeValueAsString(EventEnvelope.of(eventType, REQUEST, leadId, agentId, payload));
        EventSchemas.assertValid(wire);
    }

    @Test
    void everyEventTypeHasAnExampleAndASchema() {
        List<String> declared = Arrays.stream(EventTypes.class.getDeclaredFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
                .map(EventSchemasTest::constantValue)
                .toList();
        List<String> covered = examples().map(a -> (String) a.get()[0]).distinct().toList();
        assertThat(covered).containsExactlyInAnyOrderElementsOf(declared);
        for (String type : declared) {
            assertThat(EventSchemasTest.class.getResource("/events/" + type + ".schema.json")).as(type).isNotNull();
        }
    }

    @Test
    void unknownPayloadFieldIsRejected() throws Exception {
        String wire = json.writeValueAsString(EventEnvelope.of(EventTypes.SELLER_ENGAGED, REQUEST, LEAD, AGENT,
                Map.of("action", "VIEWED", "unexpectedField", 4)));
        assertThatThrownBy(() -> EventSchemas.assertValid(wire))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("SellerEngaged does not match its schema")
                .hasMessageContaining("unexpectedField");
    }

    @Test
    void leadLevelEventWithoutALeadIsRejected() throws Exception {
        String wire = json.writeValueAsString(EventEnvelope.of(EventTypes.LEAD_DELIVERED, REQUEST, null, null,
                Map.of("adapter", "ok", "attempt", 1)));
        assertThatThrownBy(() -> EventSchemas.assertValid(wire))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("leadId");
    }

    /** Map.of refuses null values, so nullable payload fields are added here. */
    private static Map<String, Object> withNulls(Map<String, Object> base, String... nullKeys) {
        Map<String, Object> m = new LinkedHashMap<>(base);
        for (String k : nullKeys) {
            m.put(k, null);
        }
        return m;
    }

    private static String constantValue(Field f) {
        try {
            return (String) f.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
