package uk.valuationloop.simulator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import uk.valuationloop.evidence.ComparablesRange;
import uk.valuationloop.scorecard.AgentScorecard;

/**
 * What one scenario run returns (JSON, or text through {@link SimulatorReportPrinter}).
 *
 * @param traceId the trace every log line of this run carries: grep app.log for it
 * @param checks  named observations the scenario asserts on its way through, in the order it made them
 * @param selectedAgentScorecard the selected (or, without a selection, the scenario's main) agent's own scorecard
 */
public record ScenarioReport(
        String scenario,
        String proves,
        String requestId,
        String traceId,
        String requestState,
        List<LeadLine> leads,
        String sellerSelectedLeadId,
        String instructedLeadId,
        AgentScorecard selectedAgentScorecard,
        Map<String, Object> checks,
        long wallMs) {

    /** One lead as the scenario left it. deliveryMs: lead created -> DELIVERED (null when it never was). */
    public record LeadLine(
            String leadId,
            String agentId,
            String adapter,
            int attempts,
            String state,
            Long deliveryMs,
            Instant repliedAt,
            boolean late,
            BigDecimal low,
            BigDecimal high,
            boolean evidenceAttached,
            Integer comparablesCount,
            ComparablesRange comparablesRange) {}

    /** POST /simulator/outbox-gap: the gap between commit and send, made visible by pausing the relay. */
    public record OutboxGapReport(
            String requestId,
            String traceId,
            int committedRows,
            int unsentWhilePaused,
            double outboxUnsentGauge,
            int sentAfterResume,
            List<String> eventIdsInOutbox,
            List<String> eventIdsOnTopics,
            boolean sameEventIds,
            boolean eachExactlyOnce,
            long wallMs) {}
}
