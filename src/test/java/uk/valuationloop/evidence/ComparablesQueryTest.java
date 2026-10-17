package uk.valuationloop.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Reads the query's JSON without Elasticsearch: the shape is the contract (H2-review E2-7). */
class ComparablesQueryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final GeoPoint SW11 = new GeoPoint(51.4645, -0.1650);

    private static JsonNode functionScore(Double floorArea) throws Exception {
        ComparablesQuery q = new ComparablesQuery(new ComparablesQuery.Params("FLAT", SW11, floorArea,
                LocalDate.of(2026, 10, 17), null), EvidenceProperties.defaults());
        return JSON.readTree(q.toJson()).get("function_score");
    }

    @Test
    void missingFloorAreaGetsHalfWeightInTheSameQuery() throws Exception {
        JsonNode fs = functionScore(68.0);
        JsonNode weighted = null;
        for (JsonNode f : fs.get("functions")) {
            if (f.has("weight")) {
                weighted = f;
            }
        }
        assertThat(weighted).as("a weight-only function").isNotNull();
        assertThat(weighted.get("weight").asDouble()).isEqualTo(0.5);
        assertThat(weighted.at("/filter/bool/must_not/0/exists/field").asText()).isEqualTo("floorAreaM2");
        JsonNode area = fs.at("/functions/2/gauss/floorAreaM2");
        assertThat(area.get("origin").asDouble()).isEqualTo(68.0);
        assertThat(area.get("scale").asDouble()).isEqualTo(15.0);
        assertThat(area.get("offset").asDouble()).isEqualTo(5.0);
        assertThat(fs.get("functions")).hasSize(4);
        assertThat(fs.get("score_mode").asText()).isEqualTo("multiply");
        assertThat(fs.get("boost_mode").asText()).isEqualTo("replace");
    }

    @Test
    void filtersOnTypeRadiusAndDateWindow() throws Exception {
        JsonNode filters = functionScore(68.0).at("/query/bool/filter");
        assertThat(filters.toString())
                .contains("{\"term\":{\"propertyType\":{\"value\":\"FLAT\"}}}")
                .contains("\"gte\":\"2024-10-17\"")
                .contains("\"lte\":\"2026-10-17\"");
        JsonNode geo = filters.get(1).get("geo_distance");
        assertThat(geo.get("distance").asText()).isEqualTo("1500m");
        assertThat(geo.at("/location/lat").asDouble()).isEqualTo(51.4645);
        assertThat(geo.at("/location/lon").asDouble()).isEqualTo(-0.165);
    }

    @Test
    void decaysOnDistanceAndDate() throws Exception {
        String functions = functionScore(null).get("functions").toString();
        assertThat(functions).contains("\"scale\":\"400m\"").contains("\"scale\":\"180d\"")
                .contains("\"origin\":\"2026-10-17\"");
    }

    @Test
    void noFloorAreaOnTheRequestMeansNoFloorAreaFunctions() throws Exception {
        JsonNode fs = functionScore(null);
        assertThat(fs.get("functions")).hasSize(2);
        assertThat(fs.get("functions").toString()).doesNotContain("floorAreaM2").doesNotContain("weight");
    }

    @Test
    void excludeTransactionIdIsAMustNot() throws Exception {
        ComparablesQuery q = new ComparablesQuery(new ComparablesQuery.Params("HOUSE", SW11, null,
                LocalDate.of(2026, 10, 17), "SELF-1"), EvidenceProperties.defaults());
        assertThat(JSON.readTree(q.toJson()).at("/function_score/query/bool/must_not/0/term/ppdTransactionId/value").asText())
                .isEqualTo("SELF-1");
    }
}
