package uk.valuationloop.comparison;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uk.valuationloop.agent.AgentDirectory;
import uk.valuationloop.comparison.ComparisonView.Range;
import uk.valuationloop.comparison.ComparisonView.ReplyCard;

/**
 * Builds the comparison view with one SQL join over the system of record (ARCHITECTURE.md §8, H2-review E2-1).
 *
 * Why SQL and not a Kafka consumer: agent_lead and evidence_pack are written in this same database by the reply
 * and evidence modules. A join reads what was committed a millisecond ago; a consumer-maintained copy would lag, and
 * a GET straight after a reply could show the old page.
 *
 * JdbcTemplate rather than a JPA entity for evidence_pack: that table belongs to the evidence module, so this module
 * only reads it, the way a Django view might use a raw cursor for one read-only query.
 */
@Component
public class ComparisonQuery {

    public static final String DISCLAIMER = "Each range is that agent's own opinion of the price. The evidence shows "
            + "the past sales each agent chose to attach. Nothing on this page rates an agent.";

    // Only leads that replied, in reply order (id breaks an exact tie so the order is stable). The left join keeps
    // replies without evidence; only packs the agent actually attached (attached_at set) are shown, and if an agent
    // attached more than one, the most recent one.
    private static final String SQL = """
            select l.id, l.agent_id, l.reply_low, l.reply_high, l.reply_message, l.replied_at, e.pack_json
            from agent_lead l
            left join evidence_pack e
                   on e.lead_id = l.id
                  and e.attached_at is not null
                  and e.attached_at = (select max(e2.attached_at) from evidence_pack e2
                                       where e2.lead_id = l.id and e2.attached_at is not null)
            where l.request_id = ?
              and l.replied_at is not null
            order by l.replied_at, l.id
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AgentDirectory directory;

    public ComparisonQuery(JdbcTemplate jdbc, ObjectMapper objectMapper, AgentDirectory directory) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.directory = directory;
    }

    /** Empty when the request does not exist (the controller turns that into 404). */
    @Transactional(readOnly = true)
    public Optional<ComparisonView> forRequest(String requestId) {
        List<String> state = jdbc.queryForList("select state from valuation_request where id = ?", String.class, requestId);
        if (state.isEmpty()) {
            return Optional.empty();
        }
        // RowMapper as a lambda: called once per row, like iterating a DB-API cursor
        List<ReplyCard> replies = jdbc.query(SQL, (rs, rowNum) -> new ReplyCard(
                rs.getString("id"),
                rs.getString("agent_id"),
                directory.byId(rs.getString("agent_id")).map(AgentDirectory.Agent::name).orElse(null),
                new Range(rs.getBigDecimal("reply_low"), rs.getBigDecimal("reply_high")),
                rs.getString("reply_message"),
                toInstant(rs.getObject("replied_at", OffsetDateTime.class)),
                parse(rs.getString("pack_json"))), requestId);
        return Optional.of(new ComparisonView(requestId, state.get(0), DISCLAIMER, replies));
    }

    private static Instant toInstant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    private JsonNode parse(String packJson) {
        if (packJson == null) {
            return null;
        }
        try {
            return objectMapper.readTree(packJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored evidence pack is not JSON", e);
        }
    }
}
