package uk.valuationloop.completion;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.valuationloop.evidence.ComparableDocument;
import uk.valuationloop.evidence.ComparablesSearch;

/**
 * Every minute (valuationloop.completion.scan-ms): for each instructed lead without a completion, search Land
 * Registry sales for one that matches {@link MatchRule} and record it. Land Registry publishes monthly and a sale
 * completes weeks or months after the instruction, so most scans find nothing; that is normal, not an error.
 *
 * Same shape as the deadline scanner: the @Scheduled tick can be switched off
 * (valuationloop.completion.enabled=false, as in the test yml) and tests call {@link #scanOnce()} by hand.
 */
@Component
public class CompletionScanner {

    private static final Logger log = LoggerFactory.getLogger(CompletionScanner.class);

    /** scanned: instructed leads still without a completion; matched: how many of them got one in this scan. */
    public record ScanResult(int scanned, int matched) {}

    private final CompletionService service;
    private final CompletedSales sales;
    private final MatchRule rule;
    private final boolean enabled;

    public CompletionScanner(CompletionService service, CompletedSales sales,
                             @Value("${valuationloop.completion.lookback-days:30}") int lookbackDays,
                             @Value("${valuationloop.completion.enabled:true}") boolean enabled) {
        this.service = service;
        this.sales = sales;
        this.rule = new MatchRule(lookbackDays);
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${valuationloop.completion.scan-ms:60000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            scanOnce();
        } catch (ComparablesSearch.Unavailable e) {
            log.warn("completion scan skipped: {}", e.getMessage());   // the next tick tries again
        }
    }

    public ScanResult scanOnce() {
        List<CompletionService.Candidate> waiting = service.candidates();
        int matched = 0;
        for (CompletionService.Candidate lead : waiting) {
            List<ComparableDocument> found = sales.find(lead.postcode(), lead.propertyType(),
                    rule.earliestTransfer(lead.instructedAt()));
            Optional<ComparableDocument> sale = rule.pick(lead.postcode(), lead.propertyType(), lead.instructedAt(), found);
            if (sale.isEmpty()) {
                continue;
            }
            try {
                if (service.record(lead, sale.get(), rule).isPresent()) {
                    matched++;
                    log.info("completion matched for lead {}: {} on {} ({})", lead.leadId(),
                            sale.get().ppdTransactionId(), sale.get().dateOfTransfer(), rule.name());
                }
            } catch (DataIntegrityViolationException e) {
                log.debug("lead {} was matched by another scan at the same time", lead.leadId());
            }
        }
        if (matched > 0) {
            log.info("completion scan: {} of {} waiting lead(s) matched", matched, waiting.size());
        }
        return new ScanResult(waiting.size(), matched);
    }

    public MatchRule rule() {
        return rule;
    }
}
