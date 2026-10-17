package uk.valuationloop.request;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.valuationloop.agent.AgentDirectory;
import uk.valuationloop.agent.AgentDirectory.Agent;
import uk.valuationloop.lead.AgentLead;
import uk.valuationloop.lead.AgentLeadRepository;
import uk.valuationloop.outbox.EventEnvelope;
import uk.valuationloop.outbox.OutboxWriter;
import uk.valuationloop.outbox.Topics;
import uk.valuationloop.request.RequestTransitions.RequestEvent;

/**
 * Step 1 of the loop: a seller asks for valuations.
 * One transaction creates the request, one lead per eligible agent, and the events that tell the rest of the
 * system about it (ValuationRequested, AgentsMatched, one DeliverLead command per lead). If anything fails,
 * nothing is written and nothing is sent.
 */
@Service
public class ValuationRequestService {

    private static final Logger log = LoggerFactory.getLogger(ValuationRequestService.class);

    public record CreateCommand(String sellerRef, String postcode, PropertyType propertyType, int bedrooms,
                                BigDecimal floorAreaM2, List<String> selectedAgentIds) {}

    public record Created(ValuationRequest request, List<AgentLead> leads) {}

    private final ValuationRequestRepository requests;
    private final AgentLeadRepository leads;
    private final AgentDirectory directory;
    private final OutboxWriter outbox;
    private final Clock clock;

    public ValuationRequestService(ValuationRequestRepository requests, AgentLeadRepository leads,
                                   AgentDirectory directory, OutboxWriter outbox, Clock clock) {
        this.requests = requests;
        this.leads = leads;
        this.directory = directory;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public Created create(CreateCommand cmd) {
        Instant now = clock.instant();
        ValuationRequest request = ValuationRequest.receive(cmd.sellerRef(), cmd.postcode(), cmd.propertyType(),
                cmd.bedrooms(), cmd.floorAreaM2(), now);
        // save() returns the managed copy: with an assigned id Spring Data merges rather than persists, so keep
        // the returned instance, or the state changes below land on a detached object and never reach the row
        request = requests.save(request);
        outbox.write(Topics.VALUATION_REQUESTED, request.getId(), EventEnvelope.of("ValuationRequested",
                request.getId(), null, null, Map.of(
                        "postcode", request.getPostcode(),
                        "outcode", request.getOutcode(),
                        "propertyType", request.getPropertyType().name(),
                        "bedrooms", request.getBedrooms())));

        List<Agent> eligible = eligibleAgents(request.getOutcode(), cmd.selectedAgentIds());
        if (eligible.isEmpty()) {
            throw new NoEligibleAgents(request.getOutcode());
        }
        request.apply(new RequestEvent.AgentsMatched(), now);
        outbox.write(Topics.VALUATION_REQUESTED, request.getId(), EventEnvelope.of("AgentsMatched",
                request.getId(), null, null, Map.of(
                        "agentIds", eligible.stream().map(Agent::id).toList(),
                        "eligibilityRule", cmd.selectedAgentIds() == null || cmd.selectedAgentIds().isEmpty()
                                ? "slot-holders-for-outcode" : "seller-selected")));

        List<AgentLead> created = new ArrayList<>();
        for (Agent agent : eligible) {
            AgentLead lead = AgentLead.create(request.getId(), agent.id(), now);
            leads.save(lead);
            created.add(lead);
            // the command the delivery worker acts on; keyed by leadId so one slow agent never blocks another
            outbox.write(Topics.LEAD_DELIVER, lead.getId(), EventEnvelope.of("DeliverLead",
                    request.getId(), lead.getId(), agent.id(), Map.of("adapter", agent.adapter())));
        }
        request.apply(new RequestEvent.LeadsDispatched(), now);
        log.info("request {} for {}: {} lead(s), one DeliverLead each, in the outbox",
                request.getId(), request.getOutcode(), created.size());   // the outcode only: no address in logs
        return new Created(request, created);
    }

    /** The seller's own choice wins; otherwise every slot holder for the outcode. No ordering by behaviour. */
    private List<Agent> eligibleAgents(String outcode, List<String> selectedAgentIds) {
        if (selectedAgentIds != null && !selectedAgentIds.isEmpty()) {
            return selectedAgentIds.stream()
                    .map(id -> directory.byId(id).orElseThrow(() -> new IllegalArgumentException("unknown agent " + id)))
                    .toList();
        }
        return directory.eligibleFor(outcode);
    }

    public static final class NoEligibleAgents extends RuntimeException {
        public NoEligibleAgents(String outcode) {
            super("no agent holds a slot for " + outcode);
        }
    }
}
