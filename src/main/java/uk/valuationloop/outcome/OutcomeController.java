package uk.valuationloop.outcome;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uk.valuationloop.delivery.OptimisticLockRetry;
import uk.valuationloop.response.LeadView;

/**
 * <pre>
 * POST /agents/{agentId}/leads/{leadId}/visit  {visitAt?}                    200 lead view | 404 not yours | 409 no reply yet or already booked
 * POST /outcomes/webhook  {leadRef, source, recordedAt}                      202 recorded  | 200 already recorded | 404 unknown leadRef
 *                         header X-Webhook-Token                             401 missing or wrong (checked first, {@link WebhookTokenCheck})
 * </pre>
 * The webhook answers 200 (not 409) to a repeat on purpose: a CRM that retries should hear "fine, got it", not an
 * error it will keep retrying.
 */
@RestController
public class OutcomeController {

    public record VisitBody(Instant visitAt) {}

    public record WebhookBody(@NotBlank String leadRef, @NotBlank String source, @NotNull Instant recordedAt) {}

    public record WebhookReceipt(String leadRef, String status) {}

    private final OutcomeService outcomes;

    public OutcomeController(OutcomeService outcomes) {
        this.outcomes = outcomes;
    }

    @PostMapping("/agents/{agentId}/leads/{leadId}/visit")
    public LeadView bookVisit(@PathVariable String agentId, @PathVariable String leadId,
                              @RequestBody(required = false) VisitBody body) {   // the body itself is optional
        Instant visitAt = body == null ? null : body.visitAt();
        return LeadView.of(OptimisticLockRetry.run(() -> outcomes.bookVisit(agentId, leadId, visitAt)));
    }

    @PostMapping("/outcomes/webhook")
    public ResponseEntity<WebhookReceipt> webhook(@Valid @RequestBody WebhookBody body) {
        // two copies of the same webhook at once: the loser of the row-version race retries and sees "already recorded"
        var receipt = OptimisticLockRetry.run(() -> outcomes.recordInstruction(body.leadRef(), body.source(), body.recordedAt()));
        HttpStatus status = receipt == OutcomeService.Receipt.RECORDED ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(new WebhookReceipt(body.leadRef(), receipt.name()));
    }
}
