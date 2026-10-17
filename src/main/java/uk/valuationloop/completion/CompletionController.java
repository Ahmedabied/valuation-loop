package uk.valuationloop.completion;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * POST /completions/scan   run one late-match scan now; 200 {scanned, matched} | 503 Elasticsearch unreachable
 * </pre>
 * Internal, for the demo: it saves waiting a minute for the scheduled scan. {@code @Profile({"demo", "test"})} means
 * the route only exists under those profiles, like {@link uk.valuationloop.scorecard.ScorecardInternalController}.
 */
@RestController
@Profile({"demo", "test"})
public class CompletionController {

    private final CompletionScanner scanner;

    public CompletionController(CompletionScanner scanner) {
        this.scanner = scanner;
    }

    @PostMapping("/completions/scan")
    public CompletionScanner.ScanResult scan() {
        return scanner.scanOnce();
    }
}
