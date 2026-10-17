package uk.valuationloop.scorecard;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The product query (ARCHITECTURE.md §9) on H2 with seeded facts. @JdbcTest starts only the database, Flyway and a
 * JdbcTemplate (no web, no Kafka), like a Django TestCase that only needs the ORM.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)   // use the H2 below, migrated by Flyway
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:product_query_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@Import(ProductQuery.class)
class ProductQueryTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ProductQuery query;

    void fact(String leadId, String requestId, String rank, boolean evidence, boolean engaged, boolean instructed) {
        Timestamp now = Timestamp.from(Instant.parse("2026-10-01T09:00:00Z"));
        jdbc.update("""
                insert into scorecard_fact (lead_id, request_id, agent_id, range_low, range_high, range_rank,
                    evidence_attached, seller_engaged, instructed, last_event_at, updated_at)
                values (?, ?, 'ag_fictional_01', 500000, 540000, ?, ?, ?, ?, ?, ?)
                """, leadId, requestId, rank, evidence, engaged, instructed, now, now);
    }

    @Test
    void sharesCapturedByTheHighestRangeReplySplitByEvidence() {
        jdbc.update("delete from scorecard_fact");
        // with evidence: 2 engaged (1 of them HIGHEST), 1 instructed (HIGHEST)
        fact("ld_pq_1", "vr_pq_a", "HIGHEST", true, true, true);
        fact("ld_pq_2", "vr_pq_a", "LOWEST", true, true, false);
        fact("ld_pq_3", "vr_pq_b", "MIDDLE", true, false, false);
        // without evidence: 3 engaged (1 of them HIGHEST), 1 instructed (not HIGHEST)
        fact("ld_pq_4", "vr_pq_b", "HIGHEST", false, true, false);
        fact("ld_pq_5", "vr_pq_b", "LOWEST", false, true, true);
        fact("ld_pq_6", "vr_pq_a", "MIDDLE", false, true, false);
        // unranked (the only reply to its request): not part of the question
        fact("ld_pq_7", "vr_pq_c", null, true, true, true);

        List<ProductQuery.Row> rows = query.run();

        assertThat(rows).hasSize(2);
        ProductQuery.Row without = rows.get(0), with = rows.get(1);   // ordered by evidence_attached: false, true
        assertThat(without.evidenceAttached()).isFalse();
        assertThat(without.rankedReplies()).isEqualTo(3);
        assertThat(without.engaged()).isEqualTo(3);
        assertThat(without.engagedHighest()).isEqualTo(1);
        assertThat(without.engagedHighestShare()).isEqualTo(0.3333);
        assertThat(without.instructed()).isEqualTo(1);
        assertThat(without.instructedHighest()).isZero();
        assertThat(without.instructedHighestShare()).isEqualTo(0.0);

        assertThat(with.evidenceAttached()).isTrue();
        assertThat(with.rankedReplies()).isEqualTo(3);
        assertThat(with.engaged()).isEqualTo(2);
        assertThat(with.engagedHighest()).isEqualTo(1);
        assertThat(with.engagedHighestShare()).isEqualTo(0.5);
        assertThat(with.instructed()).isEqualTo(1);
        assertThat(with.instructedHighest()).isEqualTo(1);
        assertThat(with.instructedHighestShare()).isEqualTo(1.0);
    }
}
