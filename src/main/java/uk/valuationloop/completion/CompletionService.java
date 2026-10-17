package uk.valuationloop.completion;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.valuationloop.evidence.ComparableDocument;
import uk.valuationloop.outbox.EventEnvelope;
import uk.valuationloop.outbox.EventTypes;
import uk.valuationloop.outbox.OutboxWriter;
import uk.valuationloop.outbox.Topics;

/**
 * The two database steps of the late match: list the leads still waiting for one, and record a match together with
 * its event. The search in between happens outside any transaction (CompletionScanner), so no database connection
 * is held while Elasticsearch answers.
 */
@Service
public class CompletionService {

    /** An instructed lead with no completion yet, with the request fields the rule needs. */
    public record Candidate(String leadId, String requestId, String agentId, Instant instructedAt,
                            String postcode, String propertyType) {}

    /**
     * Read-only SQL across lead and request (the same style as the comparison view's join): leads with instructed_at
     * set and no completion_match row, oldest instruction first.
     */
    private static final String CANDIDATES = """
            select l.id, l.request_id, l.agent_id, l.instructed_at, r.postcode, r.property_type
            from agent_lead l
            join valuation_request r on r.id = l.request_id
            where l.instructed_at is not null
              and not exists (select 1 from completion_match c where c.lead_id = l.id)
            order by l.instructed_at, l.id
            """;

    private final JdbcTemplate jdbc;
    private final CompletionMatchRepository matches;
    private final OutboxWriter outbox;
    private final Clock clock;

    public CompletionService(JdbcTemplate jdbc, CompletionMatchRepository matches, OutboxWriter outbox, Clock clock) {
        this.jdbc = jdbc;
        this.matches = matches;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Candidate> candidates() {
        return jdbc.query(CANDIDATES, (rs, i) -> new Candidate(
                rs.getString("id"),
                rs.getString("request_id"),
                rs.getString("agent_id"),
                rs.getObject("instructed_at", OffsetDateTime.class).toInstant(),   // works on H2 and Postgres alike
                rs.getString("postcode"),
                rs.getString("property_type")));
    }

    /**
     * The completion_match row and the CompletionMatched outbox row, in one transaction: both commit or neither.
     * Idempotent: a lead that already has a match returns empty and writes nothing (a rescan is a no-op). Two
     * scanners racing on the same lead would both pass that check; the primary key then rejects the second insert
     * and its transaction rolls back, event included.
     */
    @Transactional
    public Optional<CompletionMatch> record(Candidate lead, ComparableDocument sale, MatchRule rule) {
        if (matches.existsById(lead.leadId())) {
            return Optional.empty();
        }
        CompletionMatch match = matches.save(new CompletionMatch(lead.leadId(), lead.requestId(), lead.agentId(),
                sale.ppdTransactionId(), BigDecimal.valueOf(sale.price()), LocalDate.parse(sale.dateOfTransfer()),
                clock.instant(), rule.name()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ppdTransactionId", sale.ppdTransactionId());
        payload.put("completedPrice", sale.price());
        payload.put("completedAt", sale.dateOfTransfer());
        payload.put("matchRule", rule.name());
        outbox.write(Topics.OUTCOME_RECORDED, lead.requestId(), EventEnvelope.of(EventTypes.COMPLETION_MATCHED,
                lead.requestId(), lead.leadId(), lead.agentId(), payload));
        return Optional.of(match);
    }
}
