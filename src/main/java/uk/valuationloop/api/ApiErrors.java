package uk.valuationloop.api;

import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uk.valuationloop.comparison.SellerActions;
import uk.valuationloop.evidence.ComparablesSearch;
import uk.valuationloop.evidence.EvidencePackBuilder;
import uk.valuationloop.evidence.EvidenceService;
import uk.valuationloop.lead.LeadTransitions;
import uk.valuationloop.outcome.OutcomeService;
import uk.valuationloop.outcome.WebhookTokenCheck;
import uk.valuationloop.request.ValuationRequestController;
import uk.valuationloop.request.ValuationRequestService;
import uk.valuationloop.response.ReplyService;
import uk.valuationloop.scorecard.ScorecardController;

/**
 * Maps every module's exceptions to an HTTP status and the {@link ApiError} JSON body, in one place.
 * <p>
 * {@code @RestControllerAdvice} is a class Spring consults whenever any controller throws: it picks the
 * {@code @ExceptionHandler} whose exception type is the closest match (Django: an exception middleware).
 * The services keep throwing plain domain exceptions and know nothing about HTTP.
 *
 * <pre>
 * 401 unauthorized  webhook without the right X-Webhook-Token
 * 404 not_found     unknown request / lead / pack / agent, or a lead that is not this agent's (never 403: do not leak it exists)
 * 409 conflict      wrong state for the action (second reply, attach before reply, second selection, lost a race)
 * 422 invalid       well-formed JSON that breaks a rule (bad postcode, low &gt; high, no slot holder, failed @Valid)
 * 423 locked        contact details before the agent has replied
 * 400 bad_request   unreadable JSON body
 * 503 unavailable   Elasticsearch down while building a pack
 * </pre>
 */
@RestControllerAdvice
public class ApiErrors {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(WebhookTokenCheck.Unauthorized.class)
    public ResponseEntity<ApiError> unauthorized(RuntimeException e) {
        return body(HttpStatus.UNAUTHORIZED, "unauthorized", e.getMessage());
    }

    @ExceptionHandler({ValuationRequestController.RequestNotFound.class, ReplyService.LeadNotFound.class,
            OutcomeService.LeadNotFound.class, SellerActions.NotFound.class, EvidenceService.NotFound.class,
            ScorecardController.UnknownAgent.class})
    public ResponseEntity<ApiError> notFound(RuntimeException e) {
        return body(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    /** OptimisticLockingFailureException: two writers raced on one row and this one lost (after any retry). */
    @ExceptionHandler({ReplyService.AlreadyReplied.class, ReplyService.NotReplyable.class,
            SellerActions.Conflict.class, EvidenceService.Conflict.class, LeadTransitions.IllegalTransition.class,
            OptimisticLockingFailureException.class})
    public ResponseEntity<ApiError> conflict(RuntimeException e) {
        String message = e instanceof OptimisticLockingFailureException
                ? "the resource changed while this request ran; read it again"   // the raw message names JPA internals
                : e.getMessage();
        return body(HttpStatus.CONFLICT, "conflict", message);
    }

    @ExceptionHandler({IllegalArgumentException.class, ValuationRequestService.NoEligibleAgents.class,
            ReplyService.InvalidReply.class, EvidencePackBuilder.UnknownPostcode.class})
    public ResponseEntity<ApiError> invalid(RuntimeException e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "invalid", e.getMessage());
    }

    /** A failed @Valid on a request body: list the fields and the rule, never the rejected values. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        String fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "invalid", fields.isEmpty() ? "request body is invalid" : fields);
    }

    @ExceptionHandler(ReplyService.ContactLocked.class)
    public ResponseEntity<ApiError> locked(RuntimeException e) {
        return body(HttpStatus.LOCKED, "locked", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "bad_request", "request body is not valid JSON for this endpoint");
    }

    @ExceptionHandler(ComparablesSearch.Unavailable.class)
    public ResponseEntity<ApiError> unavailable(RuntimeException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "comparables search is unavailable; try again later");
    }

    /**
     * Everything else. Spring's own web exceptions (unknown path, wrong method, missing header) carry their status
     * through the ErrorResponse interface and keep it; anything unexpected is a 500 with a generic message, and the
     * detail goes to the log only.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> other(Exception e) {
        if (e instanceof ErrorResponse spring) {
            HttpStatusCode status = spring.getStatusCode();
            HttpStatus known = HttpStatus.resolve(status.value());
            String reason = known == null ? "error" : known.getReasonPhrase();
            return body(status, reason.toLowerCase().replace(' ', '_'), reason);
        }
        log.error("unhandled exception in a controller", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "unexpected error");
    }

    private static ResponseEntity<ApiError> body(HttpStatusCode status, String code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new ApiError(code, safe(message)));
    }

    /**
     * Messages may name an id the caller sent in the path or body ("no request vr_..."), and nothing validated that
     * id. So every message is cut to 200 characters and any character outside a plain set (letters, digits, space and
     * . , : ; ' ( ) / + = _ -) becomes '?': a caller can never get markup or control characters echoed back.
     */
    static String safe(String message) {
        if (message == null) {
            return null;
        }
        String cut = message.length() > 200 ? message.substring(0, 200) : message;
        return cut.replaceAll("[^A-Za-z0-9 .,:;'()/+=_-]", "?");
    }
}
