package uk.valuationloop.dataloader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.NoSuchFileException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import uk.valuationloop.evidence.ComparableDocument;
import uk.valuationloop.evidence.GeoPoint;

/** loader.epc.enabled=false: the EPC step never opens the file and the documents carry no EPC-derived field. */
class EpcJoinDisabledTest {

    private static final String MISSING_EPC_FILE = "build/does-not-exist/epc.csv";

    private static LoaderProperties bind(Map<String, String> props) {
        return new Binder(new MapConfigurationPropertySource(props)).bindOrCreate("loader", LoaderProperties.class);
    }

    private static LoaderProperties withEpc(boolean enabled) {
        return bind(Map.of("loader.epc-file", MISSING_EPC_FILE, "loader.epc.enabled", String.valueOf(enabled)));
    }

    @Test
    void theJoinIsOnByDefault() {
        assertThat(bind(Map.of()).epc().enabled()).isTrue();
    }

    @Test
    void disabledSkipsTheEpcFileEntirely() throws Exception {
        // the file does not exist: enabled, the step fails on it; disabled, it never opens it
        assertThatThrownBy(() -> ComparablesLoaderRunner.epcStep(withEpc(true))).isInstanceOf(NoSuchFileException.class);

        EpcLookup epc = ComparablesLoaderRunner.epcStep(withEpc(false));
        assertThat(epc.certificates()).isZero();
        assertThat(epc.latestLodgement()).isNull();
    }

    @Test
    void disabledDocumentsHaveNoEpcFieldsAndKeepThePpdType() throws Exception {
        EpcLookup epc = ComparablesLoaderRunner.epcStep(withEpc(false));
        PpdRow flat = new PpdRow("T-1", 500_000, LocalDate.of(2025, 3, 1), "ZZ1 1ZZ", "F", false, "L", "12", "FLAT 3",
                "EXAMPLE ROAD", "WANDSWORTH", "A", "A");
        PpdRow house = new PpdRow("T-2", 900_000, LocalDate.of(2025, 4, 1), "ZZ1 1ZY", "T", false, "F", "7", "",
                "SAMPLE STREET", "WANDSWORTH", "A", "A");
        PpdTransactions sales = new PpdTransactions(Set.of("WANDSWORTH"));
        sales.apply(flat);
        sales.apply(house);
        String vintage = ComparablesLoaderRunner.dataVintage(sales, epc);
        assertThat(vintage).isEqualTo("ppd-2025-2025");

        for (PpdRow sale : sales.rows()) {
            EpcLookup.Match match = epc.find(sale);
            assertThat(match.tier()).isEqualTo(EpcLookup.Tier.NONE);
            ComparableDocument doc = ComparableDocuments.from(sale, match.attributes(), new GeoPoint(51.46, -0.17), vintage);
            assertThat(doc.floorAreaM2()).isNull();
            assertThat(doc.epcBand()).isNull();
            assertThat(doc.builtForm()).isNull();
            assertThat(doc.constructionAgeBand()).isNull();
            assertThat(doc.habitableRooms()).isNull();
            assertThat(doc.propertyType()).isEqualTo("F".equals(sale.propertyType()) ? "FLAT" : "HOUSE");
        }
    }
}
