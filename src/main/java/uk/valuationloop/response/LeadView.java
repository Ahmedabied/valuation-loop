package uk.valuationloop.response;

import java.math.BigDecimal;
import java.time.Instant;
import uk.valuationloop.lead.AgentLead;

/**
 * How an agent sees one of its own leads (the reply response and GET /agents/{agentId}/leads).
 * A record doubles as the JSON shape: Jackson writes one field per component, like a DRF serializer.
 * Only ever built for the agent that owns the lead.
 */
public record LeadView(
        String leadId,
        String requestId,
        String agentId,
        String state,
        Instant deliveredAt,
        Instant replyDueAt,
        Instant repliedAt,
        boolean late,
        BigDecimal low,
        BigDecimal high,
        String message) {

    public static LeadView of(AgentLead lead) {
        return new LeadView(lead.getId(), lead.getRequestId(), lead.getAgentId(), lead.getState().name(),
                lead.getDeliveredAt(), lead.getReplyDueAt(), lead.getRepliedAt(), lead.isLate(),
                lead.getReplyLow(), lead.getReplyHigh(), lead.getReplyMessage());
    }
}
