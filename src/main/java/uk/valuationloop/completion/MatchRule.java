package uk.valuationloop.completion;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import uk.valuationloop.evidence.ComparableDocument;

/**
 * The demo's late-match rule, in one place: a Land Registry sale is the completion of an instructed lead when it is
 * at the request's postcode, of the request's property type, and transferred on or after (instructed date - N days).
 * If several sales qualify, the earliest wins (then the lowest transaction id), so the choice never depends on the
 * order the index returns them in.
 *
 * Why "minus N days": the CRM may report the instruction late, and PPD dates are the transfer date, not the day the
 * agent was told. Why this is weak: postcode + type is not an address. In production the key is the UPRN or the
 * normalised address under the operator's address licence; the demo deliberately stores no address.
 *
 * @param lookbackDays N above (valuationloop.completion.lookback-days, default 30)
 */
public record MatchRule(int lookbackDays) {

    /** PPD dates are UK calendar dates, so the instruction instant is turned into a London date before the subtraction. */
    static final ZoneId UK = ZoneId.of("Europe/London");

    /** Stored in completion_match.match_rule and sent in CompletionMatched.matchRule (at most 60 characters). */
    public String name() {
        return "postcode+propertyType+dateOfTransfer>=instructedAt-" + lookbackDays + "d";
    }

    public LocalDate earliestTransfer(Instant instructedAt) {
        return LocalDate.ofInstant(instructedAt, UK).minusDays(lookbackDays);
    }

    /**
     * Pure function: the sale this rule matches among {@code sales}, if any. It re-checks every condition in Java, so
     * the rule holds whatever the search backend returned (a fake in tests, Elasticsearch in the demo).
     */
    public Optional<ComparableDocument> pick(String postcode, String propertyType, Instant instructedAt,
                                             List<ComparableDocument> sales) {
        LocalDate earliest = earliestTransfer(instructedAt);
        return sales.stream()
                .filter(s -> postcode.equals(s.postcode()))
                .filter(s -> propertyType.equals(s.propertyType()))
                .filter(s -> !LocalDate.parse(s.dateOfTransfer()).isBefore(earliest))
                .min(Comparator.comparing((ComparableDocument s) -> LocalDate.parse(s.dateOfTransfer()))
                        .thenComparing(ComparableDocument::ppdTransactionId));
    }
}
