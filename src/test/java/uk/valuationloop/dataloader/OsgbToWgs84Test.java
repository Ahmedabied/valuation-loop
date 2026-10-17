package uk.valuationloop.dataloader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import uk.valuationloop.evidence.GeoPoint;

class OsgbToWgs84Test {

    /** The OS guide's own worked example (Annex C): grid -> OSGB36 lat/lon, before any datum shift. */
    @Test
    void inverseProjectionMatchesTheOrdnanceSurveyWorkedExample() {
        double[] latLon = OsgbToWgs84.gridToLatLon(651409.903, 313177.270);
        assertThat(Math.toDegrees(latLon[0])).isCloseTo(52 + 39 / 60.0 + 27.2531 / 3600, within(1e-6)); // 52°39'27.2531"N
        assertThat(Math.toDegrees(latLon[1])).isCloseTo(1 + 43 / 60.0 + 4.5177 / 3600, within(1e-6));   //  1°43'04.5177"E
    }

    /**
     * End to end on a real Code-Point row: SW1A 1AA is E 529090, N 179645 in Code-Point Open 2026.3 (and in
     * postcodes.io), whose WGS84 centroid postcodes.io gives as 51.50101, -0.141563. Within 0.0001 degrees
     * (about 11 m north-south, 7 m east-west): the Helmert shift is good to about 5 m.
     */
    @Test
    void knownPostcodeLandsWhereTheMapsPutIt() {
        GeoPoint p = OsgbToWgs84.convert(529090, 179645);
        assertThat(p.lat()).isCloseTo(51.50101, within(1e-4));
        assertThat(p.lon()).isCloseTo(-0.141563, within(1e-4));
    }
}
