package uk.valuationloop.completion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.valuationloop.evidence.ComparableDocument;
import uk.valuationloop.evidence.GeoPoint;

/** The late-match rule as a pure function: postcode + property type + "on or after instruction - 30 days". */
class MatchRuleTest {

    static final MatchRule RULE = new MatchRule(30);
    static final Instant INSTRUCTED = Instant.parse("2026-06-15T10:00:00Z");   // 15 June in London

    static ComparableDocument sale(String id, String postcode, String type, String date, long price) {
        return new ComparableDocument(id, price, date, postcode, postcode.split(" ")[0], new GeoPoint(51.46, -0.17),
                type, null, false, "L", null, null, null, null, "WANDSWORTH", "ppd-test");
    }

    @Test
    void earliestTransferIsTheLondonDateOfTheInstructionMinusTheLookback() {
        assertThat(RULE.earliestTransfer(INSTRUCTED)).isEqualTo(LocalDate.parse("2026-05-16"));
        // 23:30 UTC on 31 May is 00:30 on 1 June in London (BST): the London date counts
        assertThat(RULE.earliestTransfer(Instant.parse("2026-05-31T23:30:00Z"))).isEqualTo(LocalDate.parse("2026-05-02"));
        assertThat(RULE.name()).isEqualTo("postcode+propertyType+dateOfTransfer>=instructedAt-30d").hasSizeLessThanOrEqualTo(60);
    }

    @Test
    void picksTheEarliestQualifyingSaleAndIgnoresEverythingElse() {
        List<ComparableDocument> sales = List.of(
                sale("ppd-other-postcode", "SW4 7AA", "FLAT", "2026-07-01", 500_000),
                sale("ppd-house", "SW11 1AA", "HOUSE", "2026-07-01", 900_000),
                sale("ppd-too-early", "SW11 1AA", "FLAT", "2026-05-15", 450_000),
                sale("ppd-later", "SW11 1AA", "FLAT", "2026-09-01", 640_000),
                sale("ppd-b", "SW11 1AA", "FLAT", "2026-05-16", 612_000),
                sale("ppd-a", "SW11 1AA", "FLAT", "2026-05-16", 611_000));   // same day as ppd-b: lower id wins

        assertThat(RULE.pick("SW11 1AA", "FLAT", INSTRUCTED, sales)).map(ComparableDocument::ppdTransactionId).contains("ppd-a");
        assertThat(RULE.pick("SW11 1AA", "FLAT", INSTRUCTED, sales.reversed())).map(ComparableDocument::ppdTransactionId).contains("ppd-a");
        assertThat(RULE.pick("SW11 1AA", "MAISONETTE", INSTRUCTED, sales)).isEmpty();
        assertThat(RULE.pick("SW11 1AA", "FLAT", INSTRUCTED, List.of())).isEmpty();
    }
}
