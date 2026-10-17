package uk.valuationloop.evidence;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.valuationloop.lead.LeadTransitions;

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
        return service.exclude(agentId, leadId, packId, ppdTransactionId);
    }

    @PostMapping("/{packId}/attach")
    public EvidenceService.AgentPackView attach(@PathVariable String agentId, @PathVariable String leadId,
                                                @PathVariable String packId) {
        return service.attach(agentId, leadId, packId);
    }

    @ExceptionHandler(EvidenceService.NotFound.class)
    public ResponseEntity<String> notFound(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    /** Wrong state, already attached, or two requests racing on the same row (optimistic lock): all 409. */
    @ExceptionHandler({EvidenceService.Conflict.class, LeadTransitions.IllegalTransition.class,
            ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<String> conflict(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }

    @ExceptionHandler(EvidencePackBuilder.UnknownPostcode.class)
    public ResponseEntity<String> unknownPostcode(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(e.getMessage());
    }

    @ExceptionHandler(ComparablesSearch.Unavailable.class)
    public ResponseEntity<String> unavailable(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getMessage());
    }
}
