package uk.valuationloop.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Oldest unsent rows, locked for this transaction; rows another relay instance holds are skipped, not waited on.
     * That is what lets several app instances run the relay at once without sending the same row twice.
     *
     * Ordered by created_at, then id: rows written in the same instant (several events in one transaction) still have
     * a fixed order, and because ids are UUIDv7 with a per-millisecond counter ({@link UuidV7}), id order is the order
     * the events were created in.
     */
    @Query(value = """
            select * from outbox
            where sent_at is null
            order by created_at, id
            limit :batchSize
            for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> lockUnsentBatch(@Param("batchSize") int batchSize);

    long countBySentAtIsNull();
}
