package uk.valuationloop.dataloader;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** A tiny EPC file in the filtered layout; every address is fictional. */
class EpcLookupTest {

    private static final String HEADER = "CERTIFICATE_NUMBER,UPRN,UPRN_SOURCE,ADDRESS1,ADDRESS2,ADDRESS3,POSTCODE,"
            + "PROPERTY_TYPE,BUILT_FORM,TOTAL_FLOOR_AREA,NUMBER_HABITABLE_ROOMS,CONSTRUCTION_AGE_BAND,"
            + "CURRENT_ENERGY_RATING,LODGEMENT_DATE,TRANSACTION_TYPE,FLOOR_LEVEL,LOCAL_AUTHORITY,LOCAL_AUTHORITY_LABEL";

    private static EpcLookup lookup(String... rows) throws IOException {
        return EpcLookup.load(new BufferedReader(new StringReader(HEADER + "\n" + String.join("\n", rows))));
    }

    private static PpdRow sale(String postcode, String saon, String paon, String street) {
        return new PpdRow("T-1", 500_000, LocalDate.of(2025, 1, 1), postcode, "F", false, "L", paon, saon, street,
                "WANDSWORTH", "A", "A");
    }

    @Test
    void latestCertificatePerAddressWins() throws IOException {
        EpcLookup epc = lookup(
                "c1,,,\"Flat 3, 12 Example Road\",,,ZZ1 1ZZ,Flat,Mid-Terrace,60,3,England and Wales: 1900-1929,D,2015-05-01,,,,",
                "c2,,,\"Flat 3, 12 Example Road\",,,ZZ1 1ZZ,Flat,Mid-Terrace,62,3,England and Wales: 1900-1929,C,2023-06-01,,,,",
                "c3,,,\"Flat 3, 12 Example Road\",,,ZZ1 1ZZ,Flat,Mid-Terrace,58,3,England and Wales: 1900-1929,E,2010-01-01,,,,");
        EpcLookup.Match m = epc.find(sale("ZZ1 1ZZ", "FLAT 3", "12", "EXAMPLE ROAD"));
        assertThat(m.tier()).isEqualTo(EpcLookup.Tier.FULL);
        assertThat(m.attributes().floorAreaM2()).isEqualTo(62.0);
        assertThat(m.attributes().epcBand()).isEqualTo("C");
        assertThat(m.attributes().constructionAgeBand()).isEqualTo("1900-1929");
        assertThat(epc.certificates()).isEqualTo(3);
        assertThat(epc.latestLodgement()).hasToString("2023-06");
    }

    @Test
    void localityInAddress3FallsBackToTheShortKey() throws IOException {
        EpcLookup epc = lookup("c1,,,Flat 3,12 Example Road,Faketown,ZZ1 1ZZ,Maisonette,,70,4,,B,2020-01-01,,,,");
        EpcLookup.Match m = epc.find(sale("ZZ1 1ZZ", "FLAT 3", "12", "EXAMPLE ROAD"));
        assertThat(m.tier()).isEqualTo(EpcLookup.Tier.SHORT);
        assertThat(m.attributes().propertyType()).isEqualTo("Maisonette");
        assertThat(m.attributes().builtForm()).isNull();          // blank -> absent, not a sentinel
    }

    @Test
    void identityMatchOnlyWhenUnambiguous() throws IOException {
        EpcLookup epc = lookup(
                "c1,,,\"Flat 3 Testbed Court, 12 Example Road\",,,ZZ1 1ZZ,Flat,,55,2,,C,2020-01-01,,,,",
                "c2,,,7 Sample Street,,,ZZ1 1ZY,House,,90,5,,D,2020-01-01,,,,",
                "c3,,,7 Sample Mews,,,ZZ1 1ZY,House,,80,4,,D,2020-01-01,,,,");
        // PPD spells the building differently, but only one EPC address in ZZ1 1ZZ has the numbers 3 and 12
        assertThat(epc.find(sale("ZZ1 1ZZ", "FLAT 3", "12", "EXAMPLE RD")).tier()).isEqualTo(EpcLookup.Tier.IDENTITY);
        // two different EPC addresses in ZZ1 1ZY have the number 7: no guess
        assertThat(epc.find(sale("ZZ1 1ZY", "", "7", "SAMPLE ST")).tier()).isEqualTo(EpcLookup.Tier.NONE);
    }

    @Test
    void anotherPostcodeNeverMatches() throws IOException {
        EpcLookup epc = lookup("c1,,,12 Example Road,,,ZZ1 1ZZ,House,Detached,100,5,,C,2020-01-01,,,,");
        assertThat(epc.find(sale("ZZ9 9ZZ", "", "12", "EXAMPLE ROAD")).tier()).isEqualTo(EpcLookup.Tier.NONE);
    }

    @Test
    void epcRefinesThePropertyTypeOnlyWithinItsFamily() {
        assertThat(ComparableDocuments.propertyType("F", "Maisonette")).isEqualTo("MAISONETTE");
        assertThat(ComparableDocuments.propertyType("D", "Bungalow")).isEqualTo("BUNGALOW");
        assertThat(ComparableDocuments.propertyType("T", "Flat")).isEqualTo("HOUSE");   // the sale record wins
        assertThat(ComparableDocuments.propertyType("F", null)).isEqualTo("FLAT");
    }
}
