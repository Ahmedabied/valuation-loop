package uk.valuationloop.scorecard;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import uk.valuationloop.agent.AgentDirectory;

/**
 * <pre>
 * GET /agents/{agentId}/scorecard   the agent's own numbers + a fixture benchmark label; 404 for an unknown agent
 * </pre>
 * v1 has no authentication: the agentId in the path stands in for the logged-in agent, and the query is scoped to it.
 * The internal product query and fact snapshot live in {@link ScorecardInternalController} (demo/test profiles only).
 */
@RestController
public class ScorecardController {

    private final ScorecardFactRepository facts;
    private final AgentDirectory directory;

    public ScorecardController(ScorecardFactRepository facts, AgentDirectory directory) {
        this.facts = facts;
        this.directory = directory;
    }

    @GetMapping("/agents/{agentId}/scorecard")
    @Transactional(readOnly = true)
    public AgentScorecard scorecard(@PathVariable String agentId) {
        if (directory.byId(agentId).isEmpty()) {
            throw new UnknownAgent(agentId);
        }
        return AgentScorecard.of(agentId, facts.findByAgentId(agentId));
    }

    /** 404 (mapped in {@link uk.valuationloop.api.ApiErrors}). */
    public static class UnknownAgent extends RuntimeException {
        public UnknownAgent(String agentId) { super("no agent " + agentId); }
    }
}
