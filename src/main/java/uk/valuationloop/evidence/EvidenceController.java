package uk.valuationloop.evidence;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.valuationloop.delivery.OptimisticLockRetry;

/**
 * Agent-private evidence API. Every path starts with the agent and the lead, and the service checks the agent owns
 * the lead, so a wrong agent gets 404 (not 403: it should not learn the lead exists).
 * (No authentication in the v1 demo: the agentId in the path stands in for the logged-in agent.)
 *
 * <pre>
 * POST   /agents/{agentId}/leads/{leadId}/evidence                                   build from live ES   201
 * GET    /agents/{agentId}/leads/{leadId}/evidence/{packId}                          read                 200
 * DELETE /agents/{agentId}/leads/{leadId}/evidence/{packId}/comparables/{ppdId}      remove one           200 (409 once attached)
 * POST   /agents/{agentId}/leads/{leadId}/evidence/{packId}/attach                   attach to the reply  200 (409 unless REPLIED)
 * </pre>
 * 404 not this agent's, 409 wrong state / already attached / lost a race, 422 postcode outside the loaded areas,
 * 503 Elasticsearch down; the JSON error bodies come from {@link uk.valuationloop.api.ApiErrors}.
 */
@RestController
@RequestMapping("/agents/{agentId}/leads/{leadId}/evidence")
public class EvidenceController {

    private final EvidenceService service;

    public EvidenceController(EvidenceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<EvidenceService.AgentPackView> build(@PathVariable String agentId, @PathVariable String leadId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.build(agentId, leadId));
    }

    @GetMapping("/{packId}")
    public EvidenceService.AgentPackView get(@PathVariable String agentId, @PathVariable String leadId,
                                             @PathVariable String packId) {
        return service.get(agentId, leadId, packId);
    }

    @DeleteMapping("/{packId}/comparables/{ppdTransactionId}")
    public EvidenceService.AgentPackView exclude(@PathVariable String agentId, @PathVariable String leadId,
                                                 @PathVariable String packId, @PathVariable String ppdTransactionId) {
        // like reply and visit: a lost optimistic-lock race (the pack or lead changed meanwhile) is tried again
        return OptimisticLockRetry.run(() -> service.exclude(agentId, leadId, packId, ppdTransactionId));
    }

    @PostMapping("/{packId}/attach")
    public EvidenceService.AgentPackView attach(@PathVariable String agentId, @PathVariable String leadId,
                                                @PathVariable String packId) {
        return OptimisticLockRetry.run(() -> service.attach(agentId, leadId, packId));
    }
}
