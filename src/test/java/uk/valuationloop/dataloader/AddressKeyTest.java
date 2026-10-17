package uk.valuationloop.dataloader;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Every address here is fictional (Example Road, Testbed Court, ZZ postcodes). */
class AddressKeyTest {

    @Test
    void ppdPartsAndEpcFreeTextGiveTheSameKey() {
        String ppd = AddressKey.normalise("FLAT 3", "12", "EXAMPLE ROAD");
        String epc = AddressKey.normalise("Flat 3, 12 Example Road", "", "");
        assertThat(ppd).isEqualTo(epc).isEqualTo("12 3 EXAMPLE ROAD");
    }

    @Test
    void orderPunctuationCaseAndSpacingDoNotMatter() {
        assertThat(AddressKey.normalise("Apartment 7", "Testbed Court", "1-3  Sample Street"))
                .isEqualTo(AddressKey.normalise("1 - 3 SAMPLE STREET", "TESTBED COURT", "7"));
    }

    @Test
    void nullsAndBlanksAreIgnored() {
        assertThat(AddressKey.normalise(null, "12", "  ", "Example Road")).isEqualTo("12 EXAMPLE ROAD");
        assertThat(AddressKey.normalise((String) null)).isEmpty();
    }

    @Test
    void identityKeepsOnlyNumbersAndSingleLetters() {
        assertThat(AddressKey.identity("Flat B", "12a Example Road", "Faketown")).isEqualTo("12A B");
        assertThat(AddressKey.identity("Rose Cottage", "Example Lane")).isEmpty();
    }

    @Test
    void differentFlatsInOneBuildingStayDifferent() {
        assertThat(AddressKey.normalise("FLAT 3", "12", "EXAMPLE ROAD"))
                .isNotEqualTo(AddressKey.normalise("FLAT 4", "12", "EXAMPLE ROAD"));
        assertThat(AddressKey.identity("FLAT A", "12", "EXAMPLE ROAD"))
                .isNotEqualTo(AddressKey.identity("FLAT B", "12", "EXAMPLE ROAD"));
    }

    @Test
    void hashIgnoresPostcodeSpacingButNotThePostcode() {
        String key = AddressKey.normalise("12", "EXAMPLE ROAD");
        assertThat(AddressKey.hash("zz1 1zz", key)).isEqualTo(AddressKey.hash("ZZ11ZZ", key));
        assertThat(AddressKey.hash("ZZ1 1ZZ", key)).isNotEqualTo(AddressKey.hash("ZZ1 1ZY", key));
    }
}
