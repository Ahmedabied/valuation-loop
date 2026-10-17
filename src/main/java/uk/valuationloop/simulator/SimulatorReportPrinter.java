package uk.valuationloop.simulator;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import uk.valuationloop.evidence.ComparablesRange;
import uk.valuationloop.scorecard.AgentScorecard;
import uk.valuationloop.simulator.ScenarioReport.LeadLine;
import uk.valuationloop.simulator.ScenarioReport.OutboxGapReport;

/**
 * Renders a scenario report as a block of plain text a person reads in one screen (what `make demo` prints).
 * Pure functions over the report records: no Spring, so it is trivial to test or reuse.
 */
public final class SimulatorReportPrinter {

    private static final NumberFormat POUNDS = NumberFormat.getIntegerInstance(Locale.UK);   // 650000 -> "650,000"

    private SimulatorReportPrinter() {}

    public static String print(List<ScenarioReport> reports) {
        StringBuilder out = new StringBuilder();
        reports.forEach(r -> out.append(print(r)).append('\n'));
        return out.toString();
    }

    public static String print(ScenarioReport r) {
        StringBuilder out = new StringBuilder();
        out.append(String.format("== %s %s %d ms%n", r.scenario(), "=".repeat(Math.max(3, 66 - r.scenario().length())), r.wallMs()));
        out.append(r.proves()).append('\n');
        out.append(String.format("request %s  state %s  traceId %s%n", r.requestId(), r.requestState(), r.traceId()));
        out.append(String.format("  %-16s %-7s %-5s %-15s %-9s %-21s %-5s %s%n",
                "agent", "adapter", "tries", "state", "deliver", "reply range (GBP)", "late", "evidence attached"));
        for (LeadLine l : r.leads()) {
            out.append(String.format("  %-16s %-7s %-5d %-15s %-9s %-21s %-5s %s%n",
                    l.agentId(), l.adapter(), l.attempts(), l.state(),
                    l.deliveryMs() == null ? "-" : l.deliveryMs() + " ms",
                    l.low() == null ? "-" : pounds(l.low()) + " - " + pounds(l.high()),
                    l.repliedAt() == null ? "-" : (l.late() ? "yes" : "no"),
                    l.evidenceAttached() ? evidence(l.comparablesCount(), l.comparablesRange()) : "-"));
        }
        out.append(String.format("seller selected %s   instructed %s%n",
                agentOf(r, r.sellerSelectedLeadId()), agentOf(r, r.instructedLeadId())));
        out.append("checks:\n");
        for (Map.Entry<String, Object> c : r.checks().entrySet()) {
            out.append(String.format("  %-42s %s%n", c.getKey(), c.getValue()));
        }
        AgentScorecard s = r.selectedAgentScorecard();
        if (s != null) {
            out.append(String.format("scorecard of %s (own numbers, all runs so far): leads %d, replied %d, median reply %s min, "
                            + "evidence share %s, engaged %d, selected %d, visits %d, instructed %d, completed %d, "
                            + "median price gap %s%%%n",
                    s.agentId(), s.leads(), s.replied(), s.medianReplyMinutes(), s.evidenceAttachedShare(),
                    s.sellerEngaged(), s.sellerSelected(), s.visitsBooked(), s.instructed(), s.completed(),
                    s.medianPriceGapPct() == null ? "-" : s.medianPriceGapPct()));
        }
        return out.toString();
    }

    public static String print(OutboxGapReport g) {
        StringBuilder out = new StringBuilder();
        out.append(String.format("== outbox-gap %s %d ms%n", "=".repeat(54), g.wallMs()));
        out.append("Relay paused, request committed: the rows wait in the outbox, as after a crash between commit and send.\n");
        out.append(String.format("request %s  traceId %s%n", g.requestId(), g.traceId()));
        out.append(String.format("  rows committed                %d%n", g.committedRows()));
        out.append(String.format("  unsent after 1.5 s paused     %d   (outbox_unsent gauge %s)%n", g.unsentWhilePaused(), (long) g.outboxUnsentGauge()));
        out.append(String.format("  sent after resume             %d%n", g.sentAfterResume()));
        out.append(String.format("  events on the topics          %d%n", g.eventIdsOnTopics().size()));
        out.append(String.format("  same eventIds as the rows     %s%n", g.sameEventIds()));
        out.append(String.format("  each exactly once             %s%n", g.eachExactlyOnce()));
        return out.toString();
    }

    private static String evidence(Integer count, ComparablesRange range) {
        return count + " comps, range " + (range == null ? "none"
                : POUNDS.format(range.low()) + " / " + POUNDS.format(range.mid()) + " / " + POUNDS.format(range.high()));
    }

    private static String pounds(BigDecimal v) {
        return POUNDS.format(v);
    }

    private static String agentOf(ScenarioReport r, String leadId) {
        if (leadId == null) {
            return "-";
        }
        return r.leads().stream().filter(l -> l.leadId().equals(leadId)).map(LeadLine::agentId).findFirst()
                .map(a -> a + " (" + leadId + ")").orElse(leadId);
    }
}
