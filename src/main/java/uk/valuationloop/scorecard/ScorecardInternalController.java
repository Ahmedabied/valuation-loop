package uk.valuationloop.scorecard;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rightmove-internal product analytics. Never agent-facing, never linked from an agent or seller page.
 *
 * <pre>
 * GET /scorecard/product-query   the ARCHITECTURE §9 product query rows (aggregated over requests, names no agent)
 * GET /scorecard/snapshot        every fact ordered by lead_id, for the replay diff in the demo script
 * </pre>
 * {@code @Profile({"demo", "test"})}: the bean, and so the two routes, only exist when the demo or test profile is
 * active (Django: views only added to urls.py when a DEBUG-style flag is on). A normal start serves neither.
 */
@RestController
@Profile({"demo", "test"})
public class ScorecardInternalController {

    private final ScorecardFactRepository facts;
    private final ProductQuery productQuery;

    public ScorecardInternalController(ScorecardFactRepository facts, ProductQuery productQuery) {
        this.facts = facts;
        this.productQuery = productQuery;
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
}
