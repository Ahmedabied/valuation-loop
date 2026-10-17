package uk.valuationloop.simulator;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.valuationloop.api.ApiError;

/**
 * Demo profile only; internal (no seller or agent ever calls these).
 *
 * <pre>
 * POST /simulator/scenarios/{ok|slow|flaky-crm|late-reply}   200 one report   | 404 unknown scenario | 500 a step timed out
 * POST /simulator/scenarios/all                              200 four reports, run one after another
 * POST /simulator/outbox-gap                                 200 the commit/send gap made visible
 * </pre>
 * ?format=text returns the report as plain text (SimulatorReportPrinter) instead of JSON.
 */
@RestController
@Profile("demo")
@RequestMapping("/simulator")
public class SimulatorController {

    private final SimulatorScenarios scenarios;
    private final OutboxGapDemo outboxGap;

    public SimulatorController(SimulatorScenarios scenarios, OutboxGapDemo outboxGap) {
        this.scenarios = scenarios;
        this.outboxGap = outboxGap;
    }

    @PostMapping("/scenarios/{name}")
    public ResponseEntity<?> run(@PathVariable String name, @RequestParam(defaultValue = "json") String format) {
        if (name.equals("all")) {
            List<ScenarioReport> reports = scenarios.all();
            return format.equals("text") ? text(SimulatorReportPrinter.print(reports)) : ResponseEntity.ok(reports);
        }
        ScenarioReport report = scenarios.run(name);
        return format.equals("text") ? text(SimulatorReportPrinter.print(report)) : ResponseEntity.ok(report);
    }

    @PostMapping("/outbox-gap")
    public ResponseEntity<?> outboxGap(@RequestParam(defaultValue = "json") String format) {
        ScenarioReport.OutboxGapReport report = outboxGap.run();
        return format.equals("text") ? text(SimulatorReportPrinter.print(report)) : ResponseEntity.ok(report);
    }

    private static ResponseEntity<String> text(String body) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(body);
    }

    // Handlers in the controller itself win over the global ApiErrors advice, and only apply to these routes.
    @ExceptionHandler(SimulatorScenarios.UnknownScenario.class)
    public ResponseEntity<ApiError> unknown(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("unknown_scenario", e.getMessage()));
    }

    @ExceptionHandler(SimulatorScenarios.ScenarioFailed.class)
    public ResponseEntity<ApiError> failed(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError("scenario_failed", e.getMessage()));
    }
}
