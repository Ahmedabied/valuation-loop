package uk.valuationloop.comparison;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The seller's side-by-side page as JSON (GET /valuation-requests/{id}/comparison). Records double as the JSON
 * shape, like a DRF serializer: Jackson writes one field per component.
 *
 * What is deliberately NOT here: leads that have not replied, expired or failed (not listed, not counted), any
 * score, any "late" flag, any ordering other than reply order. The seller sees the replies and the evidence each
 * agent chose to attach, nothing that rates an agent.
 */
public record ComparisonView(String requestId, String state, String disclaimer, List<ReplyCard> replies) {

    /** One agent's reply. evidence is the attached pack exactly as stored (parsed JSON), or null when none. */
    public record ReplyCard(String leadId, String agentId, String agentName, Range range, String message,
                            Instant repliedAt, JsonNode evidence) {}

    /** The agent's own range, in whole pounds. */
    public record Range(BigDecimal low, BigDecimal high) {}
}
