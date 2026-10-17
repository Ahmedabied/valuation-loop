package uk.valuationloop.scorecard;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import uk.valuationloop.agent.AgentDirectory;

/**
 * <pre>
 * GET /agents/{agentId}/scorecard   the agent's own numbers + a fixture benchmark label; 404 for an unknown agent
 * GET /scorecard/product-query      internal: the ARCHITECTURE §9 product query rows (aggregated, names no agent)
 * GET /scorecard/snapshot           internal: every fact ordered by lead_id, for the replay diff in the demo script
 * </pre>
 * v1 has no authentication: the agentId in the path stands in for the logged-in agent, and the query is scoped to it.
 * The two /scorecard endpoints are for Rightmove's own team and the demo, never linked from an agent or seller page.
 */
@RestController
public class ScorecardController {

    private final ScorecardFactRepository facts;
    private final AgentDirectory directory;
    private final ProductQuery productQuery;

    public ScorecardController(ScorecardFactRepository facts, AgentDirectory directory, ProductQuery productQuery) {
        this.facts = facts;
        this.directory = directory;
        this.productQuery = productQuery;
    }

    @GetMapping("/agents/{agentId}/scorecard")
    @Transactional(readOnly = true)
    public AgentScorecard scorecard(@PathVariable String agentId) {
        if (directory.byId(agentId).isEmpty()) {
            throw new UnknownAgent(agentId);
        }
        return AgentScorecard.of(agentId, facts.findByAgentId(agentId));
    }

    @GetMapping("/scorecard/product-query")
    public List<ProductQuery.Row> productQuery() {
        return productQuery.run();
    }

    @GetMapping("/scorecard/snapshot")
    @Transactional(readOnly = true)
    public List<FactSnapshot> snapshot() {
        return facts.findAllByOrderByLeadId().stream().map(FactSnapshot::of).toList();
    }

    @ExceptionHandler(UnknownAgent.class)
    public ResponseEntity<Map<String, String>> notFound(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("reason", e.getMessage()));
    }

    /** 404 */
    public static class UnknownAgent extends RuntimeException {
        public UnknownAgent(String agentId) { super("no agent " + agentId); }
    }
}
