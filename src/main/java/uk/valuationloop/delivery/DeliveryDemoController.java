package uk.valuationloop.delivery;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.valuationloop.observability.LoopMetrics;

/**
 * Demo profile only; internal. When the jar runs split (api + worker), the simulator lives in the api process but
 * the adapters and the DLT handler live in the worker, so the flaky-crm scenario reaches them over loopback HTTP:
 *
 * <pre>
 * PUT    /internal/demo/delivery/flaky-failures?n=1000   the flaky adapter fails the first n calls per lead
 * DELETE /internal/demo/delivery/flaky-failures          back to valuationloop.delivery.flaky-failures
 * GET    /internal/demo/delivery/dlt-records             {"dltRecords": n}, this process's dlt_records_total
 * </pre>
 * In one process the simulator calls FlakyAdapter and LoopMetrics directly and nothing calls these routes.
 */
@RestController
@Profile("demo")
@RequestMapping("/internal/demo/delivery")
public class DeliveryDemoController {

    private final FlakyAdapter flaky;
    private final LoopMetrics metrics;

    public DeliveryDemoController(FlakyAdapter flaky, LoopMetrics metrics) {
        this.flaky = flaky;
        this.metrics = metrics;
    }

    @PutMapping("/flaky-failures")
    public void overrideFlaky(@RequestParam int n) {
        flaky.overrideFailuresPerLead(n);
    }

    @DeleteMapping("/flaky-failures")
    public void clearFlaky() {
        flaky.clearOverride();
    }

    @GetMapping("/dlt-records")
    public Map<String, Double> dltRecords() {
        return Map.of("dltRecords", metrics.dltRecordsSoFar());
    }
}
