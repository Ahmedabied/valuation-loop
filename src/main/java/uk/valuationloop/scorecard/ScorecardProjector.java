package uk.valuationloop.scorecard;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.valuationloop.outbox.EventEnvelope;
import uk.valuationloop.outbox.EventTypes;

/**
 * Applies one event to scorecard_fact: an upsert by lead_id (the natural key), so the read model needs no dedupe
 * table. Replaying every event from the start, or receiving one twice, leaves the same rows (H2-review E2-4).
 *
 * The upsert is "select for update, then create-or-change, then save" inside one transaction. That works the same on
 * H2 and Postgres (no MERGE / ON CONFLICT dialects). The listener runs one thread, so two inserts of the same new
 * lead cannot race; if they ever did, the primary key rejects the second and the container redelivers the record,
 * which then finds the row.
 */
@Service
public class ScorecardProjector {

    private static final Logger log = LoggerFactory.getLogger(ScorecardProjector.class);

    /** The events the scorecard reads. Everything else on the topics is ignored. */
    static final Set<String> HANDLED = Set.of(
            EventTypes.LEAD_DELIVERED, EventTypes.LEAD_DELIVERY_FAILED, EventTypes.LEAD_REPLIED,
            EventTypes.EVIDENCE_ATTACHED, EventTypes.LEAD_EXPIRED, EventTypes.SELLER_ENGAGED,
            EventTypes.SELLER_SELECTED, EventTypes.VISIT_BOOKED, EventTypes.INSTRUCTION_RECORDED,
            EventTypes.COMPLETION_MATCHED);

    private final ScorecardFactRepository facts;
    private final Clock clock;

    public ScorecardProjector(ScorecardFactRepository facts, Clock clock) {
        this.facts = facts;
        this.clock = clock;
    }

    @Transactional
    public void apply(EventEnvelope e) {
        if (!HANDLED.contains(e.eventType())) {
            log.debug("scorecard ignores {}", e.eventType());
            return;
        }
        if (e.leadId() == null || e.agentId() == null || e.requestId() == null || e.occurredAt() == null) {
            log.warn("scorecard skips {} {}: lead-level event without leadId/agentId/requestId/occurredAt",
                    e.eventType(), e.eventId());
            return;
        }
        // the database keeps microseconds; truncating here means a value read back from a row equals the value
        // computed from an event, whichever arrived first
        Instant occurredAt = micros(e.occurredAt());
        Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();

        ScorecardFact fact = facts.findForUpdate(e.leadId())
                .orElseGet(() -> ScorecardFact.create(e.leadId(), e.requestId(), e.agentId(), occurredAt));
        boolean rangeChanged = false;
        switch (e.eventType()) {
            case EventTypes.LEAD_DELIVERED -> fact.delivered(occurredAt);
            case EventTypes.LEAD_DELIVERY_FAILED -> { }   // the row exists (it is one of the agent's leads); nothing else
            case EventTypes.LEAD_REPLIED -> {
                // the reply time is the payload's repliedAt; occurredAt is only a fallback for an event without one
                Instant repliedAt = p.get("repliedAt") != null ? instant(p.get("repliedAt")) : occurredAt;
                fact.replied(repliedAt, decimal(p.get("low")), decimal(p.get("high")),
                        p.get("late") instanceof Boolean b ? b : null, instant(p.get("deliveredAt")));
                rangeChanged = true;
            }
            case EventTypes.EVIDENCE_ATTACHED -> fact.evidenceAttached();
            case EventTypes.LEAD_EXPIRED -> fact.expired();
            case EventTypes.SELLER_ENGAGED -> fact.sellerEngaged();
            case EventTypes.SELLER_SELECTED -> fact.sellerSelected();
            case EventTypes.VISIT_BOOKED -> fact.visitBooked();
            case EventTypes.INSTRUCTION_RECORDED -> fact.instructed();
            // the gap to the range midpoint is derived in touched(), so it appears whether the reply came first or not
            case EventTypes.COMPLETION_MATCHED ->
                    fact.completed(decimal(p.get("completedPrice")), LocalDate.parse(p.get("completedAt").toString()));
            default -> throw new IllegalStateException("unreachable: " + e.eventType());
        }
        fact.touched(occurredAt, clock.instant());
        fact = facts.save(fact);   // keep the managed copy (same discipline as ValuationRequestService)
        if (rangeChanged) {
            rerank(fact.getRequestId());
        }
    }

    /**
     * Recomputes range_rank for every fact of the request. Ranks are a function of the set of ranges only, so
     * whichever reply arrives last, the ranks end up the same.
     */
    private void rerank(String requestId) {
        List<ScorecardFact> ofRequest = facts.findByRequestId(requestId);   // JPA flushes the save above first
        Map<String, RangeRank> ranks = RangeRank.of(ofRequest.stream()
                .filter(ScorecardFact::hasRange)
                .map(f -> new RangeRank.Ranged(f.getLeadId(), f.getRangeLow(), f.getRangeHigh()))
                .toList());
        for (ScorecardFact f : ofRequest) {
            f.rank(ranks.get(f.getLeadId()));   // null when unranked; managed entities are written at commit
        }
    }

    private static Instant micros(Instant t) {
        return t.truncatedTo(ChronoUnit.MICROS);
    }

    private static BigDecimal decimal(Object v) {
        return v == null ? null : new BigDecimal(v.toString());   // JSON numbers arrive as Integer, Long or Double
    }

    private static Instant instant(Object v) {
        return v == null ? null : micros(Instant.parse(v.toString()));
    }
}
