package uk.valuationloop.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Test helper: checks an event envelope (the JSON string exactly as it goes on the wire) against the schema for
 * its eventType, from src/main/resources/events/&lt;EventType&gt;.schema.json.
 *
 * Use it in any test that sees an emitted event: {@code EventSchemas.assertValid(record.value());}
 * The validator library (networknt) is built on Jackson 2, so this helper parses the wire string with its own
 * Jackson 2 mapper; the string itself is what the app (Jackson 3) wrote.
 * A failure lists every violation, so a contract break reads as "payload.low: is missing" rather than a stack trace.
 */
public final class EventSchemas {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    // Draft 2020-12 treats "format" (uuid, date-time) as a label only unless assertions are switched on.
    private static final SchemaValidatorsConfig CONFIG = SchemaValidatorsConfig.builder()
            .formatAssertionsEnabled(true)
            .build();
    private static final Map<String, JsonSchema> CACHE = new ConcurrentHashMap<>();

    private EventSchemas() {}

    public static void assertValid(String envelopeJson) {
        JsonNode envelope;
        try {
            envelope = JSON.readTree(envelopeJson);
        } catch (IOException e) {
            throw new AssertionError("event is not valid JSON: " + envelopeJson, e);
        }
        JsonNode type = envelope.get("eventType");
        if (type == null || !type.isTextual()) {
            throw new AssertionError("event has no eventType: " + envelopeJson);
        }
        Set<ValidationMessage> violations = schemaFor(type.asText()).validate(envelope);
        if (!violations.isEmpty()) {
            String list = violations.stream().map(ValidationMessage::getMessage).sorted()
                    .collect(Collectors.joining("\n  - ", "\n  - ", ""));
            throw new AssertionError(type.asText() + " does not match its schema:" + list + "\nevent: " + envelopeJson);
        }
    }

    private static JsonSchema schemaFor(String eventType) {
        return CACHE.computeIfAbsent(eventType, t -> {
            String path = "/events/" + t + ".schema.json";
            try (InputStream in = EventSchemas.class.getResourceAsStream(path)) {
                if (in == null) {
                    throw new AssertionError("no schema for eventType " + t + " (expected " + path + ")");
                }
                return FACTORY.getSchema(in, CONFIG);
            } catch (IOException e) {
                throw new AssertionError("cannot read " + path, e);
            }
        });
    }
}
