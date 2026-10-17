package uk.valuationloop.scorecard;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
        fact(leadId, requestId, rank, evidence, engaged, instructed, null);
    }

    /** gapPct non-null: the lead completed (the scorecard derives the gap; here it is seeded directly). */
    void fact(String leadId, String requestId, String rank, boolean evidence, boolean engaged, boolean instructed,
              String gapPct) {
        Timestamp now = Timestamp.from(Instant.parse("2026-10-01T09:00:00Z"));
        jdbc.update("""
                insert into scorecard_fact (lead_id, request_id, agent_id, range_low, range_high, range_rank,
                    evidence_attached, seller_engaged, instructed, completed_price, price_gap_pct,
                    last_event_at, updated_at)
                values (?, ?, 'ag_fictional_01', 500000, 540000, ?, ?, ?, ?, ?, ?, ?, ?)
                """, leadId, requestId, rank, evidence, engaged, instructed,
                gapPct == null ? null : new BigDecimal("520000"), gapPct == null ? null : new BigDecimal(gapPct), now, now);
    }

    @Test
    void sharesCapturedByTheHighestRangeReplySplitByEvidence() {
        jdbc.update("delete from scorecard_fact");
        // with evidence: 2 engaged (1 of them HIGHEST), 1 instructed (HIGHEST)
        fact("ld_pq_1", "vr_pq_a", "HIGHEST", true, true, true, "-1.50");
        fact("ld_pq_2", "vr_pq_a", "LOWEST", true, true, false);
        fact("ld_pq_3", "vr_pq_b", "MIDDLE", true, false, false);
        // without evidence: 3 engaged (1 of them HIGHEST), 1 instructed (not HIGHEST)
        fact("ld_pq_4", "vr_pq_b", "HIGHEST", false, true, false);
        fact("ld_pq_5", "vr_pq_b", "LOWEST", false, true, true, "2.00");
        fact("ld_pq_6", "vr_pq_a", "MIDDLE", false, true, false);
        // unranked (the only reply to its request): not part of the question
        fact("ld_pq_7", "vr_pq_c", null, true, true, true, "9.99");
        // completed, without evidence, HIGHEST: three gaps in that group -> the median is the middle one
        fact("ld_pq_8", "vr_pq_d", "HIGHEST", false, true, true, "-8.00");
        fact("ld_pq_9", "vr_pq_e", "HIGHEST", false, false, true, "-4.00");
        fact("ld_pq_10", "vr_pq_f", "HIGHEST", false, false, true, "-6.50");
        // and a second completed non-highest reply without evidence: two gaps -> the median is their average
        fact("ld_pq_11", "vr_pq_d", "LOWEST", false, false, true, "3.25");

        List<ProductQuery.Row> rows = query.run();

        assertThat(rows).hasSize(2);
        ProductQuery.Row without = rows.get(0), with = rows.get(1);   // ordered by evidence_attached: false, true
        assertThat(without.evidenceAttached()).isFalse();
        assertThat(without.rankedReplies()).isEqualTo(7);
        assertThat(without.engaged()).isEqualTo(4);
        assertThat(without.engagedHighest()).isEqualTo(2);
        assertThat(without.engagedHighestShare()).isEqualTo(0.5);
        assertThat(without.instructed()).isEqualTo(5);
        assertThat(without.instructedHighest()).isEqualTo(3);
        assertThat(without.instructedHighestShare()).isEqualTo(0.6);
        assertThat(without.completed()).isEqualTo(5);
        assertThat(without.medianPriceGapPctHighest()).isEqualTo(-6.5);   // -8.00, -6.50, -4.00
        assertThat(without.medianPriceGapPctOther()).isEqualTo(2.63);     // (2.00 + 3.25) / 2 = 2.625 -> 2.63

        assertThat(with.evidenceAttached()).isTrue();
        assertThat(with.rankedReplies()).isEqualTo(3);
        assertThat(with.engaged()).isEqualTo(2);
        assertThat(with.engagedHighest()).isEqualTo(1);
        assertThat(with.engagedHighestShare()).isEqualTo(0.5);
        assertThat(with.instructed()).isEqualTo(1);
        assertThat(with.instructedHighest()).isEqualTo(1);
        assertThat(with.instructedHighestShare()).isEqualTo(1.0);
        assertThat(with.completed()).isEqualTo(1);                         // ld_pq_7 is unranked, so not counted
        assertThat(with.medianPriceGapPctHighest()).isEqualTo(-1.5);
        assertThat(with.medianPriceGapPctOther()).isNull();                // no completed non-highest evidenced reply
    }
}
