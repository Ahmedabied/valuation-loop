package uk.valuationloop.dataloader;

import uk.valuationloop.evidence.GeoPoint;

/**
 * British National Grid easting/northing (OSGB36, what Code-Point Open gives) -> WGS84 latitude/longitude (what
 * Elasticsearch's geo_point and every map expects). Hand-rolled in two textbook steps, no library:
 *
 * <ol>
 *   <li>inverse Transverse Mercator on the Airy 1830 ellipsoid: E/N -> OSGB36 lat/lon;</li>
 *   <li>lat/lon -> 3D Cartesian, 7-parameter Helmert shift OSGB36 -> WGS84, back to lat/lon on GRS80;</li>
 * </ol>
 * Source of every constant and formula: Ordnance Survey, "A guide to coordinate systems in Great Britain":
 * Annex A (ellipsoid and National Grid constants), Annex C (inverse projection, with the worked example
 * E 651409.903 N 313177.270 that OsgbToWgs84Test checks), and the guide's Helmert parameter table for
 * WGS84 -> OSGB36 (tx -446.448, ty +125.157, tz -542.060 m, s +20.4894 ppm, rx -0.1502", ry -0.2470",
 * rz -0.8421"), every sign flipped here for the reverse direction.
 * The Helmert shift is good to about 5 m, which is far finer than a 400 m distance decay needs. (OSTN15 would give
 * centimetres; not needed for comparables.)
 */
public final class OsgbToWgs84 {

    // Airy 1830 ellipsoid and the National Grid projection (OS guide Annex A)
    private static final double AIRY_A = 6_377_563.396;
    private static final double AIRY_B = 6_356_256.909;
    private static final double F0 = 0.9996012717;                 // scale factor on the central meridian
    private static final double PHI0 = Math.toRadians(49);         // true origin 49 N
    private static final double LAMBDA0 = Math.toRadians(-2);      // 2 W
    private static final double E0 = 400_000;                      // false easting
    private static final double N0 = -100_000;                     // false northing

    // GRS80 (= WGS84 to the millimetre)
    private static final double GRS80_A = 6_378_137.000;
    private static final double GRS80_B = 6_356_752.3141;

    // Helmert OSGB36 -> WGS84 (the guide's WGS84 -> OSGB36 values with every sign flipped)
    private static final double TX = 446.448, TY = -125.157, TZ = 542.060;   // metres
    private static final double S = -20.4894e-6;                              // ppm -> ratio
    private static final double RX = arcSeconds(0.1502), RY = arcSeconds(0.2470), RZ = arcSeconds(0.8421);

    private OsgbToWgs84() {}

    public static GeoPoint convert(double easting, double northing) {
        double[] osgb = gridToLatLon(easting, northing);                 // radians, on Airy 1830
        double[] xyz = toCartesian(osgb[0], osgb[1], AIRY_A, AIRY_B);
        double[] wgs = helmert(xyz);
        double[] latLon = toLatLon(wgs, GRS80_A, GRS80_B);
        return new GeoPoint(Math.toDegrees(latLon[0]), Math.toDegrees(latLon[1]));
    }

    /** Step 1, OS guide Annex C. Returns {lat, lon} in radians on Airy 1830 (OSGB36). Package-private for the test. */
    static double[] gridToLatLon(double easting, double northing) {
        double a = AIRY_A, b = AIRY_B;
        double e2 = 1 - (b * b) / (a * a);
        double n = (a - b) / (a + b);

        double phi = (northing - N0) / (a * F0) + PHI0;
        double m = meridionalArc(phi, b, n);
        while (Math.abs(northing - N0 - m) >= 0.00001) {                 // iterate until within 0.01 mm
            phi += (northing - N0 - m) / (a * F0);
            m = meridionalArc(phi, b, n);
        }

        double sin = Math.sin(phi), cos = Math.cos(phi), tan = Math.tan(phi), sec = 1 / cos;
        double nu = a * F0 / Math.sqrt(1 - e2 * sin * sin);
        double rho = a * F0 * (1 - e2) / Math.pow(1 - e2 * sin * sin, 1.5);
        double eta2 = nu / rho - 1;
        double t2 = tan * tan, t4 = t2 * t2, t6 = t4 * t2;

        double vii = tan / (2 * rho * nu);
        double viii = tan / (24 * rho * Math.pow(nu, 3)) * (5 + 3 * t2 + eta2 - 9 * t2 * eta2);
        double ix = tan / (720 * rho * Math.pow(nu, 5)) * (61 + 90 * t2 + 45 * t4);
        double x = sec / nu;
        double xi = sec / (6 * Math.pow(nu, 3)) * (nu / rho + 2 * t2);
        double xii = sec / (120 * Math.pow(nu, 5)) * (5 + 28 * t2 + 24 * t4);
        double xiia = sec / (5040 * Math.pow(nu, 7)) * (61 + 662 * t2 + 1320 * t4 + 720 * t6);

        double de = easting - E0;
        double lat = phi - vii * de * de + viii * Math.pow(de, 4) - ix * Math.pow(de, 6);
        double lon = LAMBDA0 + x * de - xi * Math.pow(de, 3) + xii * Math.pow(de, 5) - xiia * Math.pow(de, 7);
        return new double[] {lat, lon};
    }

    /** The meridional arc M (OS guide Annex C, equation C3). */
    private static double meridionalArc(double phi, double b, double n) {
        double n2 = n * n, n3 = n2 * n;
        double dPhi = phi - PHI0, sPhi = phi + PHI0;
        return b * F0 * ((1 + n + 1.25 * n2 + 1.25 * n3) * dPhi
                - (3 * n + 3 * n2 + 21.0 / 8 * n3) * Math.sin(dPhi) * Math.cos(sPhi)
                + (15.0 / 8 * n2 + 15.0 / 8 * n3) * Math.sin(2 * dPhi) * Math.cos(2 * sPhi)
                - 35.0 / 24 * n3 * Math.sin(3 * dPhi) * Math.cos(3 * sPhi));
    }

    /** lat/lon (radians, height 0) -> Earth-centred x, y, z in metres. */
    private static double[] toCartesian(double lat, double lon, double a, double b) {
        double e2 = 1 - (b * b) / (a * a);
        double nu = a / Math.sqrt(1 - e2 * Math.sin(lat) * Math.sin(lat));
        return new double[] {
                nu * Math.cos(lat) * Math.cos(lon),
                nu * Math.cos(lat) * Math.sin(lon),
                (1 - e2) * nu * Math.sin(lat)};
    }

    /** The 7-parameter similarity transform (small-angle form), OS guide section 6.2. */
    private static double[] helmert(double[] p) {
        double x = p[0], y = p[1], z = p[2];
        return new double[] {
                TX + (1 + S) * x - RZ * y + RY * z,
                TY + RZ * x + (1 + S) * y - RX * z,
                TZ - RY * x + RX * y + (1 + S) * z};
    }

    /** Cartesian -> lat/lon (radians), iterating the latitude until it stops moving. */
    private static double[] toLatLon(double[] p, double a, double b) {
        double e2 = 1 - (b * b) / (a * a);
        double pXY = Math.sqrt(p[0] * p[0] + p[1] * p[1]);
        double lat = Math.atan2(p[2], pXY * (1 - e2));
        for (int i = 0; i < 10; i++) {
            double nu = a / Math.sqrt(1 - e2 * Math.sin(lat) * Math.sin(lat));
            lat = Math.atan2(p[2] + e2 * nu * Math.sin(lat), pXY);
        }
        return new double[] {lat, Math.atan2(p[1], p[0])};
    }

    private static double arcSeconds(double seconds) {
        return Math.toRadians(seconds / 3600);
    }
}
