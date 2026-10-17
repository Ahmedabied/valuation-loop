package uk.valuationloop.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes an event into the outbox table. Must be called inside the transaction that changes the state the event
 * describes: then the state change and the event commit together, or neither does.
 *
 * Calling it outside a transaction is a programming error and fails loudly. (A framework that silently parks such
 * events until a retry timer fires is exactly the trap this hand-rolled outbox exists to avoid.)
 */
@Component
public class OutboxWriter {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public OutboxEvent write(String topic, String partitionKey, EventEnvelope envelope) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OutboxWriter.write called outside a transaction; the event would not be atomic with the state change");
        }
        try {
            String json = objectMapper.writeValueAsString(envelope);
            return repository.save(new OutboxEvent(envelope.eventId(), topic, partitionKey, envelope.eventType(), json, Instant.now()));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("event payload is not serialisable: " + envelope.eventType(), e);
        }
    }
}
