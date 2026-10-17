package uk.valuationloop.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One row = one event waiting to go to Kafka (or already gone, when sent_at is set). */
@Entity
@Table(name = "outbox")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String topic;

    @Column(name = "partition_key", nullable = false)
    private String partitionKey;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false, length = 100000)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    protected OutboxEvent() {} // JPA

    public OutboxEvent(UUID id, String topic, String partitionKey, String eventType, String payload, Instant createdAt) {
        this.id = id;
        this.topic = topic;
        this.partitionKey = partitionKey;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public void markSent(Instant at) {
        this.sentAt = at;
        this.attempts++;
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    }

    public UUID getId() { return id; }
    public String getTopic() { return topic; }
    public String getPartitionKey() { return partitionKey; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
}
