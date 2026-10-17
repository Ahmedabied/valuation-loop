package uk.valuationloop.dataloader;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Price Paid lines in the real 16-column layout, with fictional addresses and ZZ postcodes. */
class PpdRowParserTest {

    static String line(String id, String price, String type, String district, String category, String status) {
        return String.join(",", q("{" + id + "}"), q(price), q("2025-03-14 00:00"), q("ZZ1 1ZZ"), q(type), q("N"),
                q("L"), q("12"), q("FLAT 3"), q("EXAMPLE ROAD"), q(""), q("LONDON"), q(district),
                q("GREATER LONDON"), q(category), q(status));
    }

    private static String q(String s) {
        return "\"" + s + "\"";
    }

    private static PpdRow row(String id, String price, String type, String district, String category, String status) {
        return PpdRowParser.parse(line(id, price, type, district, category, status)).orElseThrow();
    }

    @Test
    void parsesEveryColumnWeUse() {
        PpdRow r = row("AAAA-0001", "450000", "F", "WANDSWORTH", "A", "A");
        assertThat(r.transactionId()).isEqualTo("AAAA-0001");
        assertThat(r.price()).isEqualTo(450_000L);
        assertThat(r.dateOfTransfer()).isEqualTo(LocalDate.of(2025, 3, 14));
        assertThat(r.postcode()).isEqualTo("ZZ1 1ZZ");
        assertThat(r.propertyType()).isEqualTo("F");
        assertThat(r.newBuild()).isFalse();
        assertThat(r.tenure()).isEqualTo("L");
        assertThat(r.paon()).isEqualTo("12");
        assertThat(r.saon()).isEqualTo("FLAT 3");
        assertThat(r.street()).isEqualTo("EXAMPLE ROAD");
        assertThat(r.district()).isEqualTo("WANDSWORTH");
    }

    @Test
    void quotedCommasStayInsideTheField() {
        String withComma = line("AAAA-0002", "300000", "T", "LAMBETH", "A", "A").replace("\"EXAMPLE ROAD\"", "\"EXAMPLE ROAD, NORTH\"");
        assertThat(PpdRowParser.parse(withComma).orElseThrow().street()).isEqualTo("EXAMPLE ROAD, NORTH");
    }

    @Test
    void malformedLinesAreEmptyNotExceptions() {
        assertThat(PpdRowParser.parse("not,a,ppd,line")).isEmpty();
        assertThat(PpdRowParser.parse(line("AAAA-0003", "lots", "F", "LAMBETH", "A", "A"))).isEmpty();
    }

    @Test
    void keepsOnlyTheDistrictsCategoryAAndRealPropertyTypes() {
        PpdTransactions t = new PpdTransactions(Set.of("WANDSWORTH", "LAMBETH"));
        t.apply(row("K-1", "500000", "F", "WANDSWORTH", "A", "A"));   // kept
        t.apply(row("K-2", "700000", "T", "LAMBETH", "A", "A"));      // kept
        t.apply(row("X-1", "900000", "F", "WANDSWORTH", "B", "A"));   // category B (repossession, buy-to-let, ...)
        t.apply(row("X-2", "800000", "O", "LAMBETH", "A", "A"));      // O = other, not a dwelling type we compare
        t.apply(row("X-3", "400000", "F", "SOUTHWARK", "A", "A"));    // another district
        assertThat(t.rows()).extracting(PpdRow::transactionId).containsExactly("K-1", "K-2");
        assertThat(t.rowsSeen()).isEqualTo(5);
    }

    @Test
    void laterChangeReplacesAndDeleteRemoves() {
        PpdTransactions t = new PpdTransactions(Set.of("WANDSWORTH"));
        t.apply(row("C-1", "500000", "F", "WANDSWORTH", "A", "A"));
        t.apply(row("C-1", "515000", "F", "WANDSWORTH", "A", "C"));   // corrected price
        t.apply(row("D-1", "600000", "S", "WANDSWORTH", "A", "A"));
        t.apply(row("D-1", "600000", "S", "WANDSWORTH", "A", "D"));   // withdrawn
        assertThat(t.rows()).singleElement().satisfies(r -> {
            assertThat(r.transactionId()).isEqualTo("C-1");
            assertThat(r.price()).isEqualTo(515_000L);
        });
    }

    @Test
    void aChangeThatNoLongerPassesTheFilterDropsTheSale() {
        PpdTransactions t = new PpdTransactions(Set.of("WANDSWORTH"));
        t.apply(row("C-2", "500000", "F", "WANDSWORTH", "A", "A"));
        t.apply(row("C-2", "500000", "F", "WANDSWORTH", "B", "C"));   // re-categorised as B
        assertThat(t.size()).isZero();
    }
}
