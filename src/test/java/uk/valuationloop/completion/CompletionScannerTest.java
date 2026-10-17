package uk.valuationloop.completion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import uk.valuationloop.comparison.LoopApiTestBase;
import uk.valuationloop.evidence.ComparableDocument;
import uk.valuationloop.outbox.EventTypes;
import uk.valuationloop.outbox.OutboxEvent;
import uk.valuationloop.outbox.Topics;
import uk.valuationloop.outcome.OutcomeService;
import uk.valuationloop.support.EventSchemas;

/**
 * The late match against the real database and outbox, with Land Registry faked: an instructed lead gets exactly one
 * completion_match row and one CompletionMatched event, a lead without an instruction gets none, and a rescan
 * writes nothing. The scanner is built by hand around a fake CompletedSales, so this shares the LoopApiTestBase
 * context (no extra Spring context, no Elasticsearch).
 */
class CompletionScannerTest extends LoopApiTestBase {

    static final String POSTCODE = "SW4 7ZZ";   // only this test's requests use it, so the fake answers only them
    static final String AG1 = "ag_fictional_01", AG2 = "ag_fictional_02";

    @Autowired CompletionService service;
    @Autowired CompletionMatchRepository matches;
    @Autowired OutcomeService outcomes;

    /** The fake index: every sale at POSTCODE; the scanner's rule decides which (if any) is the completion. */
    final List<ComparableDocument> index = new ArrayList<>();
    final List<String> searchedPostcodes = new ArrayList<>();

    CompletedSales fakeSales() {
        return (postcode, type, onOrAfter) -> {
            searchedPostcodes.add(postcode);
            return index.stream().filter(s -> s.postcode().equals(postcode)).toList();
        };
    }

    String request(List<String> agentIds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sellerRef", "completion-test");
        body.put("postcode", POSTCODE);
        body.put("propertyType", "FLAT");
        body.put("bedrooms", 2);
        body.put("selectedAgentIds", agentIds);
        String response = mvc.perform(post("/valuation-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("requestId").asString();
    }

    @Test
    void anInstructedLeadIsMatchedOnceWithItsEventAndARescanIsANoOp() throws Exception {
        String requestId = request(List.of(AG1, AG2));
        String instructed = leadIdOf(requestId, AG1);
        String notInstructed = leadIdOf(requestId, AG2);
        outcomes.recordInstruction(instructed, "fake-crm", clock.instant());
        LocalDate instructedOn = LocalDate.ofInstant(leads.findById(instructed).orElseThrow().getInstructedAt(), MatchRule.UK);

        index.add(MatchRuleTest.sale("ppd-before-window", POSTCODE, "FLAT", instructedOn.minusDays(31).toString(), 480_000));
        index.add(MatchRuleTest.sale("ppd-house", POSTCODE, "HOUSE", instructedOn.plusDays(40).toString(), 990_000));
        index.add(MatchRuleTest.sale("ppd-completion", POSTCODE, "FLAT", instructedOn.plusDays(40).toString(), 612_000));
        index.add(MatchRuleTest.sale("ppd-next-resale", POSTCODE, "FLAT", instructedOn.plusDays(200).toString(), 650_000));
        CompletionScanner scanner = new CompletionScanner(service, fakeSales(), 30, false);

        CompletionScanner.ScanResult first = scanner.scanOnce();

        assertThat(first.matched()).isEqualTo(1);
        assertThat(searchedPostcodes).contains(POSTCODE);
        CompletionMatch match = matches.findById(instructed).orElseThrow();
        assertThat(match.getPpdTransactionId()).isEqualTo("ppd-completion");
        assertThat(match.getCompletedPrice()).isEqualByComparingTo("612000");
        assertThat(match.getCompletedAt()).isEqualTo(instructedOn.plusDays(40));
        assertThat(match.getRequestId()).isEqualTo(requestId);
        assertThat(match.getAgentId()).isEqualTo(AG1);
        assertThat(match.getMatchRule()).isEqualTo("postcode+propertyType+dateOfTransfer>=instructedAt-30d");
        assertThat(matches.findById(notInstructed)).isEmpty();   // no instruction, no completion

        List<OutboxEvent> events = outboxRows(EventTypes.COMPLETION_MATCHED, requestId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getTopic()).isEqualTo(Topics.OUTCOME_RECORDED);
        EventSchemas.assertValid(events.get(0).getPayload());
        JsonNode envelope = tree(events.get(0).getPayload());
        assertThat(envelope.get("leadId").asString()).isEqualTo(instructed);
        assertThat(envelope.get("agentId").asString()).isEqualTo(AG1);
        assertThat(envelope.at("/payload/ppdTransactionId").asString()).isEqualTo("ppd-completion");
        assertThat(envelope.at("/payload/completedPrice").asLong()).isEqualTo(612_000);
        assertThat(envelope.at("/payload/completedAt").asString()).isEqualTo(instructedOn.plusDays(40).toString());

        // a rescan: the lead is no longer a candidate, nothing new is written
        CompletionScanner.ScanResult second = scanner.scanOnce();
        assertThat(second.matched()).isZero();
        assertThat(service.candidates()).noneMatch(c -> c.leadId().equals(instructed));
        assertThat(outboxRows(EventTypes.COMPLETION_MATCHED, requestId)).hasSize(1);

        // and recording the same lead again directly (two scanners racing past the candidate query) is refused too
        CompletionService.Candidate again = new CompletionService.Candidate(instructed, requestId, AG1,
                Instant.now(), POSTCODE, "FLAT");
        assertThat(service.record(again, index.get(2), scanner.rule())).isEmpty();
        assertThat(outboxRows(EventTypes.COMPLETION_MATCHED, requestId)).hasSize(1);
    }

    @Test
    void anInstructedLeadWithNoSaleYetStaysWaiting() throws Exception {
        String requestId = request(List.of(AG1));
        String lead = leadIdOf(requestId, AG1);
        outcomes.recordInstruction(lead, "fake-crm", clock.instant());
        CompletionScanner scanner = new CompletionScanner(service, (postcode, type, onOrAfter) -> List.of(), 30, false);

        assertThat(scanner.scanOnce().matched()).isZero();
        assertThat(matches.findById(lead)).isEmpty();
        assertThat(service.candidates()).anyMatch(c -> c.leadId().equals(lead) && c.postcode().equals(POSTCODE));
        assertThat(outboxRows(EventTypes.COMPLETION_MATCHED, requestId)).isEmpty();
    }
}
