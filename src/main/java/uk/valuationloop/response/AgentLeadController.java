package uk.valuationloop.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.valuationloop.delivery.OptimisticLockRetry;
import uk.valuationloop.response.ReplyService.ContactRelease;

/**
 * The agent-facing API. v1 has no authentication: the agentId in the path stands in for the logged-in agent,
 * and every query is scoped to it.
 *
 * <pre>
 * POST /agents/{agentId}/leads/{leadId}/reply    201 lead view | 404 not yours | 409 second reply or not delivered | 422 bad range
 * GET  /agents/{agentId}/leads/{leadId}/contact  200 contact   | 404 not yours | 423 Locked until you reply
 * GET  /agents/{agentId}/leads                   200 own leads only
 * </pre>
 *
 * 423 Locked (not 403) for the contact: the agent IS allowed to see it, just not yet; 423 says "this resource is
 * locked", and the body says what unlocks it.
 */
@RestController
@RequestMapping("/agents/{agentId}/leads")
public class AgentLeadController {

    public record ReplyBody(BigDecimal low, BigDecimal high, String message) {}

    private final ReplyService replies;

    public AgentLeadController(ReplyService replies) {
        this.replies = replies;
    }

    @PostMapping("/{leadId}/reply")
    public ResponseEntity<LeadView> reply(@PathVariable String agentId, @PathVariable String leadId,
                                          @RequestBody ReplyBody body) {
        var reply = new ReplyService.Reply(body.low(), body.high(), body.message());
        // Two agents replying to the same request at once both touch the request row; the loser of that
        // optimistic-lock race simply tries again. A genuine second reply re-reads the lead and gets 409.
        var lead = OptimisticLockRetry.run(() -> replies.reply(agentId, leadId, reply));
        return ResponseEntity.status(HttpStatus.CREATED).body(LeadView.of(lead));
    }

    @GetMapping("/{leadId}/contact")
    public ContactRelease contact(@PathVariable String agentId, @PathVariable String leadId) {
        return replies.contact(agentId, leadId);
    }

    @GetMapping
    public List<LeadView> myLeads(@PathVariable String agentId) {
        return replies.leadsOf(agentId).stream().map(LeadView::of).toList();
    }

    // @ExceptionHandler: Spring's version of Django's "raise Http404" mapping, scoped to this controller.

    @ExceptionHandler(ReplyService.LeadNotFound.class)
    public ResponseEntity<Map<String, String>> notFound(RuntimeException e) {
        return error(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler({ReplyService.AlreadyReplied.class, ReplyService.NotReplyable.class,
            OptimisticLockingFailureException.class})
    public ResponseEntity<Map<String, String>> conflict(RuntimeException e) {
        return error(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(ReplyService.InvalidReply.class)
    public ResponseEntity<Map<String, String>> invalid(RuntimeException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, e);
    }

    @ExceptionHandler(ReplyService.ContactLocked.class)
    public ResponseEntity<Map<String, String>> locked(RuntimeException e) {
        return error(HttpStatus.LOCKED, e);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, RuntimeException e) {
        return ResponseEntity.status(status).body(Map.of("reason", e.getMessage()));
    }
}
