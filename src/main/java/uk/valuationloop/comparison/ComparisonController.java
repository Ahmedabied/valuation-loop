package uk.valuationloop.comparison;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import uk.valuationloop.comparison.SellerActions.EngagementAction;
import uk.valuationloop.delivery.OptimisticLockRetry;
import uk.valuationloop.request.ValuationRequest;

/**
 * The seller-facing comparison API.
 *
 * <pre>
 * GET  /valuation-requests/{id}/comparison   200 replies in reply order + disclaimer | 404
 * POST /valuation-requests/{id}/engagement   202 SellerEngaged   | 404 lead not in request | 409 lead has not replied
 * POST /valuation-requests/{id}/selection    200 request closed  | 404 | 409 not REPLIES_IN (incl. a second selection) or no reply
 * </pre>
 */
@RestController
@RequestMapping("/valuation-requests/{requestId}")
public class ComparisonController {

    public record EngagementBody(@NotBlank String leadId, @NotNull EngagementAction action) {}

    public record SelectionBody(@NotBlank String leadId) {}

    public record SelectionView(String requestId, String state, String selectedLeadId) {}

    private final ComparisonQuery query;
    private final SellerActions actions;

    public ComparisonController(ComparisonQuery query, SellerActions actions) {
        this.query = query;
        this.actions = actions;
    }

    @GetMapping("/comparison")
    public ComparisonView comparison(@PathVariable String requestId) {
        return query.forRequest(requestId).orElseThrow(() -> new SellerActions.NotFound("no request " + requestId));
    }

    @PostMapping("/engagement")
    public ResponseEntity<Void> engage(@PathVariable String requestId, @Valid @RequestBody EngagementBody body) {
        actions.engage(requestId, body.leadId(), body.action());
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @PostMapping("/selection")
    public SelectionView select(@PathVariable String requestId, @Valid @RequestBody SelectionBody body) {
        // a reply landing at the same moment also touches the request row; the loser of that race tries again
        ValuationRequest r = OptimisticLockRetry.run(() -> actions.select(requestId, body.leadId()));
        return new SelectionView(r.getId(), r.getState().name(), r.getSelectedLeadId());
    }

    @ExceptionHandler(SellerActions.NotFound.class)
    public ResponseEntity<Map<String, String>> notFound(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("reason", e.getMessage()));
    }

    @ExceptionHandler({SellerActions.Conflict.class, OptimisticLockingFailureException.class})
    public ResponseEntity<Map<String, String>> conflict(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("reason", e.getMessage()));
    }
}
