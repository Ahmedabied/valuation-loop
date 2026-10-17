package uk.valuationloop.delivery;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.valuationloop.request.RequestCloser;

/**
 * Every few seconds: expire delivered leads whose reply_due_at has passed, then close any request whose leads are
 * now all terminal. The valuation-request rule is "reply by the end of the next working day"; v1 simplifies it to
 * valuationloop.reply-window-hours (24).
 *
 * Time comes from the injected Clock, never Instant.now(), so a test moves the clock 25 hours forward and calls
 * {@link #scanOnce()} instead of sleeping a day. The @Scheduled tick can be switched off
 * (valuationloop.delivery.deadline-scanner.enabled=false) so tests drive the scan by hand.
 */
@Component
public class DeadlineScanner {

    private static final Logger log = LoggerFactory.getLogger(DeadlineScanner.class);

    public record ScanResult(int expired, int requestsClosed) {}

    private final DeadlineService deadlines;
    private final RequestCloser closer;
    private final Clock clock;
    private final boolean enabled;

    public DeadlineScanner(DeadlineService deadlines, RequestCloser closer, Clock clock,
                           @Value("${valuationloop.delivery.deadline-scanner.enabled:true}") boolean enabled) {
        this.deadlines = deadlines;
        this.closer = closer;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${valuationloop.delivery.deadline-scan-ms:5000}")
    public void tick() {
        if (enabled) {
            scanOnce();
        }
    }

    public ScanResult scanOnce() {
        Instant now = clock.instant();
        Set<String> affectedRequests = new LinkedHashSet<>();   // a request with three leads past due is checked once
        int expired = 0;
        for (String leadId : deadlines.pastDueLeadIds(now)) {
            var requestId = OptimisticLockRetry.run(() -> deadlines.expire(leadId, now));
            if (requestId.isPresent()) {
                expired++;
                affectedRequests.add(requestId.get());
            }
        }
        int closed = 0;
        for (String requestId : affectedRequests) {
            if (OptimisticLockRetry.run(() -> closer.closeIfAllLeadsTerminal(requestId, RequestCloser.ALL_LEADS_TERMINAL))) {
                closed++;
            }
        }
        if (expired > 0) {
            log.info("deadline scan: {} lead(s) expired, {} request(s) closed", expired, closed);
        }
        return new ScanResult(expired, closed);
    }
}
