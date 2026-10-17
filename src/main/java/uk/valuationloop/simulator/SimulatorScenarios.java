package uk.valuationloop.simulator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import uk.valuationloop.agent.AgentDirectory;
import uk.valuationloop.comparison.ComparisonQuery;
import uk.valuationloop.comparison.ComparisonView;
import uk.valuationloop.comparison.SellerActions;
import uk.valuationloop.comparison.SellerActions.EngagementAction;
import uk.valuationloop.delivery.DeadlineService;
import uk.valuationloop.delivery.FlakyAdapter;
import uk.valuationloop.delivery.OptimisticLockRetry;
import uk.valuationloop.evidence.Comparable;
import uk.valuationloop.evidence.ComparablesRange;
import uk.valuationloop.evidence.EvidenceService;
import uk.valuationloop.evidence.EvidenceService.AgentPackView;
import uk.valuationloop.lead.AgentLead;
import uk.valuationloop.lead.AgentLeadRepository;
import uk.valuationloop.lead.LeadState;
import uk.valuationloop.observability.EventTracing;
import uk.valuationloop.observability.LoopMetrics;
import uk.valuationloop.outcome.OutcomeService;
import uk.valuationloop.request.PropertyType;
import uk.valuationloop.request.ValuationRequest;
import uk.valuationloop.request.ValuationRequestRepository;
import uk.valuationloop.request.ValuationRequestService;
import uk.valuationloop.response.LeadView;
import uk.valuationloop.response.ReplyService;
import uk.valuationloop.scorecard.AgentScorecard;
import uk.valuationloop.scorecard.ScorecardFact;
import uk.valuationloop.scorecard.ScorecardFactRepository;
import uk.valuationloop.simulator.ScenarioReport.LeadLine;

/**
 * The four demo scenarios. Each one plays the seller, the agents and their CRMs against the real loop: it calls the
 * same services the controllers call, and between steps it WAITS for the asynchronous parts (outbox relay -> Kafka
 * -> delivery consumer; -> scorecard consumer) by polling the database, the way a person refreshing a page would.
 *
 * Each scenario runs in its own trace (EventTracing.newTrace), so its traceId, printed in the report, finds every
 * log line it caused, on the request thread, the relay and both consumers.
 *
 * Fixture agents (AgentDirectory, all fictional): 01 ok, 02 slow (2 s), 03 flaky (fails twice, lands on try 3), 05 ok.
 */
@Service
@Profile("demo")
public class SimulatorScenarios {

    public static final List<String> NAMES = List.of("ok", "slow", "flaky-crm", "late-reply");

    static final String AGENT_OK = "ag_fictional_01";
    static final String AGENT_SLOW = "ag_fictional_02";
    static final String AGENT_FLAKY = "ag_fictional_03";
    static final String AGENT_OK_2 = "ag_fictional_05";

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Duration POLL = Duration.ofMillis(100);
    private static final BigDecimal FLOOR_AREA_M2 = new BigDecimal("68.5");

    private final ValuationRequestService requestService;
    private final ValuationRequestRepository requests;
    private final AgentLeadRepository leads;
    private final AgentDirectory directory;
    private final ReplyService replies;
    private final EvidenceService evidence;
    private final ComparisonQuery comparison;
    private final SellerActions seller;
    private final OutcomeService outcomes;
    private final DeadlineService deadlines;
    private final FlakyAdapter flaky;
    private final ScorecardFactRepository facts;
    private final LoopMetrics metrics;
    private final ObjectMapper json;
    private final Clock clock;
    private final String postcode;

    public SimulatorScenarios(ValuationRequestService requestService, ValuationRequestRepository requests,
                              AgentLeadRepository leads, AgentDirectory directory, ReplyService replies,
                              EvidenceService evidence, ComparisonQuery comparison, SellerActions seller,
                              OutcomeService outcomes, DeadlineService deadlines, FlakyAdapter flaky,
                              ScorecardFactRepository facts, LoopMetrics metrics, ObjectMapper json, Clock clock,
                              @Value("${valuationloop.simulator.postcode:SW11 1AA}") String postcode) {
        this.requestService = requestService;
        this.requests = requests;
        this.leads = leads;
        this.directory = directory;
        this.replies = replies;
        this.evidence = evidence;
        this.comparison = comparison;
        this.seller = seller;
        this.outcomes = outcomes;
        this.deadlines = deadlines;
        this.flaky = flaky;
        this.facts = facts;
        this.metrics = metrics;
        this.json = json;
        this.clock = clock;
        this.postcode = postcode;
    }

    public ScenarioReport run(String name) {
        return switch (name) {
            case "ok" -> EventTracing.newTrace("simulator.ok", () -> fullLoop("ok",
                    "Claims 1-3: delivery through a flaky CRM, an evidenced reply, the seller's choice joined to the instruction",
                    AGENT_OK, AGENT_OK_2, AGENT_FLAKY));
            case "slow" -> EventTracing.newTrace("simulator.slow", () -> fullLoop("slow",
                    "Claim 1: a slow CRM delays its own lead; delivery latency per lead is measured",
                    AGENT_OK, AGENT_SLOW, AGENT_OK_2));
            case "flaky-crm" -> EventTracing.newTrace("simulator.flaky-crm", this::flakyCrm);
            case "late-reply" -> EventTracing.newTrace("simulator.late-reply", this::lateReply);
            default -> throw new UnknownScenario(name);
        };
    }

    public List<ScenarioReport> all() {
        return NAMES.stream().map(this::run).toList();
    }

    /**
     * The whole loop for three agents: all delivered; the high agent replies high; the evidenced agent builds a pack
     * from live Elasticsearch, removes one comparable, replies at the range its comparables imply and attaches the
     * pack; the third replies without evidence. The seller views all three, asks the evidenced agent for a visit and
     * selects it; that agent books the visit; its CRM reports the instruction by webhook (leadRef); the scorecard
     * consumer joins it all onto the lead's fact.
     */
    private ScenarioReport fullLoop(String scenario, String proves, String highAgent, String evidencedAgent,
                                    String plainAgent) {
        long started = System.nanoTime();
        Map<String, Object> checks = new LinkedHashMap<>();
        String requestId = createRequest(scenario, List.of(highAgent, evidencedAgent, plainAgent));

        awaitLeads(requestId, "every lead DELIVERED", ls -> ls.stream().allMatch(l -> l.getState() == LeadState.DELIVERED));
        checks.put("all leads delivered", true);
        String highLead = leadOf(requestId, highAgent).getId();
        String evidencedLead = leadOf(requestId, evidencedAgent).getId();
        String plainLead = leadOf(requestId, plainAgent).getId();

        // the evidenced agent: build from live ES, curate one comparable out, reply at the comparables' range, attach
        AgentPackView built = evidence.build(evidencedAgent, evidencedLead);
        List<Comparable> comps = built.pack().comparables();
        if (comps.size() <= ComparablesRange.MINIMUM_COMPARABLES) {
            throw new ScenarioFailed(scenario + ": the pack for " + postcode + " has only " + comps.size()
                    + " comparables; is the comparables index loaded (make load)?");
        }
        String dropped = comps.get(comps.size() - 1).ppdTransactionId();   // the lowest-ranked one
        AgentPackView curated = evidence.exclude(evidencedAgent, evidencedLead, built.packId(), dropped);
        ComparablesRange range = curated.pack().comparablesRange();
        checks.put("pack comparables (built, after curation)", comps.size() + " -> " + curated.pack().comparables().size());
        checks.put("comparable excluded by the agent", dropped);

        double mid = range.mid();
        reply(highAgent, highLead, pounds(mid * 1.12), pounds(mid * 1.25), "Strong buyer demand on this street right now.");
        reply(evidencedAgent, evidencedLead, pounds(range.low()), pounds(range.high()),
                "Our range follows the recent sales attached.");
        evidence.attach(evidencedAgent, evidencedLead, built.packId());
        reply(plainAgent, plainLead, pounds(mid * 0.98), pounds(mid * 1.10), "Happy to visit and refine this.");

        // the seller
        ComparisonView view = comparison.forRequest(requestId).orElseThrow();
        checks.put("seller sees replies (in reply order)", view.replies().stream().map(ComparisonView.ReplyCard::agentId).toList());
        checks.put("seller sees evidence on", view.replies().stream().filter(c -> c.evidence() != null)
                .map(ComparisonView.ReplyCard::agentId).toList());
        view.replies().forEach(card -> seller.engage(requestId, card.leadId(), EngagementAction.VIEWED));
        seller.engage(requestId, evidencedLead, EngagementAction.VISIT_REQUESTED);
        OptimisticLockRetry.run(() -> seller.select(requestId, evidencedLead));

        // the selected agent and its CRM
        OptimisticLockRetry.run(() -> outcomes.bookVisit(evidencedAgent, evidencedLead, clock.instant().plus(Duration.ofDays(2))));
        var receipt = OptimisticLockRetry.run(() -> outcomes.recordInstruction(evidencedLead, "simulated-crm", clock.instant()));
        checks.put("CRM webhook (leadRef = the lead id)", receipt.name());

        ScorecardFact fact = awaitFact(evidencedLead, "the scorecard fact shows the instruction",
                f -> f.isInstructed() && f.isSellerSelected() && f.isVisitBooked() && f.isEvidenceAttached());
        checks.put("scorecard fact of the selected lead", factExcerpt(fact));
        return report(scenario, proves, requestId, checks, evidencedAgent, started);
    }

    /**
     * The flaky CRM never accepts: its lead goes lead.deliver.v1 -> -retry-0 -> -retry-1 -> -dlt and is marked
     * DELIVERY_FAILED, while the other agent's lead is delivered and answered and the request carries on.
     */
    private ScenarioReport flakyCrm() {
        long started = System.nanoTime();
        Map<String, Object> checks = new LinkedHashMap<>();
        double dltBefore = metrics.dltRecordsSoFar();
        String requestId;
        flaky.overrideFailuresPerLead(1_000);   // explicit and temporary: restored below whatever happens
        try {
            requestId = createRequest("flaky-crm", List.of(AGENT_OK, AGENT_FLAKY));
            awaitLeads(requestId, "flaky lead DELIVERY_FAILED and ok lead DELIVERED", ls ->
                    stateOf(ls, AGENT_FLAKY) == LeadState.DELIVERY_FAILED && stateOf(ls, AGENT_OK) == LeadState.DELIVERED);
        } finally {
            flaky.clearOverride();
        }
        awaitTrue("dlt_records counts the dead letter", () -> metrics.dltRecordsSoFar() >= dltBefore + 1);
        checks.put("flaky lead attempts before the DLT", leadOf(requestId, AGENT_FLAKY).getDeliveryAttempts());
        checks.put("dlt_records_total (before -> after)", (long) dltBefore + " -> " + (long) metrics.dltRecordsSoFar());

        String okLead = leadOf(requestId, AGENT_OK).getId();
        reply(AGENT_OK, okLead, BigDecimal.valueOf(585_000), BigDecimal.valueOf(640_000), "Happy to visit this week.");
        ComparisonView view = comparison.forRequest(requestId).orElseThrow();
        String failedLead = leadOf(requestId, AGENT_FLAKY).getId();
        checks.put("request still proceeding", requests.findById(requestId).orElseThrow().getState().name());
        checks.put("seller page mentions the failed lead", toJson(view).contains(failedLead));
        return report("flaky-crm", "Claim 1: a CRM that never answers ends in the DLT after 3 attempts; the others are unaffected",
                requestId, checks, AGENT_FLAKY, started);
    }

    /**
     * One agent misses its reply window and answers afterwards. The simulator asks the deadline service to judge the
     * lead at its due time + 1 s, exactly the call the scheduled DeadlineScanner makes with the clock's "now", so no
     * production path changes; then the late reply is accepted and flagged late in the agent's own record only.
     */
    private ScenarioReport lateReply() {
        long started = System.nanoTime();
        Map<String, Object> checks = new LinkedHashMap<>();
        String requestId = createRequest("late-reply", List.of(AGENT_OK, AGENT_OK_2));
        awaitLeads(requestId, "every lead DELIVERED", ls -> ls.stream().allMatch(l -> l.getState() == LeadState.DELIVERED));
        String onTimeLead = leadOf(requestId, AGENT_OK).getId();
        AgentLead lateLead = leadOf(requestId, AGENT_OK_2);

        reply(AGENT_OK, onTimeLead, BigDecimal.valueOf(590_000), BigDecimal.valueOf(640_000), "Recent sales support this range.");
        boolean expired = OptimisticLockRetry.run(() -> deadlines.expire(lateLead.getId(), lateLead.getReplyDueAt().plusSeconds(1))).isPresent();
        checks.put("lead expired at reply_due_at + 1 s", expired + " (" + leadOf(requestId, AGENT_OK_2).getState() + ")");

        reply(AGENT_OK_2, lateLead.getId(), BigDecimal.valueOf(600_000), BigDecimal.valueOf(660_000), "Apologies for the wait.");
        LeadView agentView = replies.leadsOf(AGENT_OK_2).stream().map(LeadView::of)
                .filter(v -> v.leadId().equals(lateLead.getId())).findFirst().orElseThrow();
        ComparisonView view = comparison.forRequest(requestId).orElseThrow();
        checks.put("agent's own lead view: late", agentView.late());
        checks.put("seller page lists the late reply", view.replies().stream().anyMatch(c -> c.leadId().equals(lateLead.getId())));
        checks.put("seller page has a late flag", toJson(view).contains("\"late\""));

        ScorecardFact fact = awaitFact(lateLead.getId(), "the scorecard fact has the late reply", f -> f.getRepliedAt() != null);
        checks.put("scorecard fact of the late lead", factExcerpt(fact));
        return report("late-reply", "Claim 3: a reply after the deadline still joins to its lead; late is agent-private",
                requestId, checks, AGENT_OK_2, started);
    }

    // ---------- steps ----------

    private String createRequest(String scenario, List<String> agentIds) {
        var created = requestService.create(new ValuationRequestService.CreateCommand(
                "sim-" + scenario + "-" + Long.toString(System.currentTimeMillis(), 36), postcode, PropertyType.FLAT,
                2, FLOOR_AREA_M2, agentIds));
        return created.request().getId();
    }

    private void reply(String agentId, String leadId, BigDecimal low, BigDecimal high, String message) {
        OptimisticLockRetry.run(() -> replies.reply(agentId, leadId, new ReplyService.Reply(low, high, message)));
    }

    /** Whole thousands of pounds, the way agents quote. */
    private static BigDecimal pounds(double value) {
        return BigDecimal.valueOf(Math.round(value / 1000.0) * 1000);
    }

    private AgentLead leadOf(String requestId, String agentId) {
        return leads.findByRequestIdAndAgentId(requestId, agentId).orElseThrow();
    }

    private static LeadState stateOf(List<AgentLead> ls, String agentId) {
        return ls.stream().filter(l -> l.getAgentId().equals(agentId)).map(AgentLead::getState).findFirst().orElse(null);
    }

    // ---------- waiting for the asynchronous parts ----------

    private List<AgentLead> awaitLeads(String requestId, String what, Predicate<List<AgentLead>> done) {
        Instant deadline = Instant.now().plus(WAIT);
        while (true) {
            List<AgentLead> ls = leads.findByRequestIdOrderByCreatedAt(requestId);
            if (done.test(ls)) {
                return ls;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new ScenarioFailed("timed out after " + WAIT.toSeconds() + " s waiting for " + what + "; leads: "
                        + ls.stream().map(l -> l.getAgentId() + "=" + l.getState()).toList());
            }
            sleep();
        }
    }

    private ScorecardFact awaitFact(String leadId, String what, Predicate<ScorecardFact> done) {
        Instant deadline = Instant.now().plus(WAIT);
        while (true) {
            var fact = facts.findById(leadId);
            if (fact.isPresent() && done.test(fact.get())) {
                return fact.get();
            }
            if (Instant.now().isAfter(deadline)) {
                throw new ScenarioFailed("timed out after " + WAIT.toSeconds() + " s waiting for " + what
                        + " (is the scorecard listener running?)");
            }
            sleep();
        }
    }

    private static void awaitTrue(String what, BooleanSupplier done) {
        Instant deadline = Instant.now().plus(WAIT);
        while (!done.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new ScenarioFailed("timed out waiting for " + what);
            }
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScenarioFailed("interrupted");
        }
    }

    // ---------- the report ----------

    private ScenarioReport report(String scenario, String proves, String requestId, Map<String, Object> checks,
                                  String fallbackAgent, long startedNanos) {
        ValuationRequest request = requests.findById(requestId).orElseThrow();
        List<AgentLead> ls = leads.findByRequestIdOrderByCreatedAt(requestId);
        List<LeadLine> lines = ls.stream().map(this::line).toList();
        String instructed = ls.stream().filter(l -> l.getInstructedAt() != null).map(AgentLead::getId).findFirst().orElse(null);
        String scorecardAgent = request.getSelectedLeadId() == null ? fallbackAgent
                : ls.stream().filter(l -> l.getId().equals(request.getSelectedLeadId())).map(AgentLead::getAgentId).findFirst().orElse(fallbackAgent);
        AgentScorecard scorecard = AgentScorecard.of(scorecardAgent, facts.findByAgentId(scorecardAgent));
        return new ScenarioReport(scenario, proves, requestId, EventTracing.currentTraceId(), request.getState().name(),
                lines, request.getSelectedLeadId(), instructed, scorecard, checks,
                Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
    }

    private LeadLine line(AgentLead l) {
        Long deliveryMs = l.getDeliveredAt() == null ? null : Duration.between(l.getCreatedAt(), l.getDeliveredAt()).toMillis();
        Integer count = null;
        ComparablesRange range = null;
        if (l.getEvidencePackId() != null) {
            var pack = evidence.get(l.getAgentId(), l.getId(), l.getEvidencePackId()).pack();
            count = pack.comparables().size();
            range = pack.comparablesRange();
        }
        String adapter = directory.byId(l.getAgentId()).map(AgentDirectory.Agent::adapter).orElse("?");
        return new LeadLine(l.getId(), l.getAgentId(), adapter, l.getDeliveryAttempts(), l.getState().name(), deliveryMs,
                l.getRepliedAt(), l.isLate(), l.getReplyLow(), l.getReplyHigh(), l.getEvidencePackId() != null, count, range);
    }

    private static Map<String, Object> factExcerpt(ScorecardFact f) {
        Map<String, Object> m = new LinkedHashMap<>();   // LinkedHashMap keeps the order; Map.of() refuses nulls
        m.put("replyMinutes", f.getReplyMinutes());
        m.put("withinDeadline", f.getWithinDeadline());
        m.put("evidenceAttached", f.isEvidenceAttached());
        m.put("rangeRank", f.getRangeRank());
        m.put("sellerEngaged", f.isSellerEngaged());
        m.put("sellerSelected", f.isSellerSelected());
        m.put("visitBooked", f.isVisitBooked());
        m.put("instructed", f.isInstructed());
        return m;
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 404 */
    public static class UnknownScenario extends RuntimeException {
        public UnknownScenario(String name) { super("no scenario '" + name + "'; try one of " + NAMES + " or all"); }
    }

    /** A step did not happen in time; the message says which and what the leads looked like. */
    public static class ScenarioFailed extends RuntimeException {
        public ScenarioFailed(String message) { super(message); }
    }
}
