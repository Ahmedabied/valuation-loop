package uk.valuationloop.dataloader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import uk.valuationloop.evidence.GeoPoint;
import uk.valuationloop.request.Postcodes;

/**
 * Reads OS Code-Point Open (OGL, with OS / Royal Mail / National Statistics attribution) straight from the zip:
 * Data/CSV/&lt;area&gt;.csv, no header, columns Postcode, Positional_quality_indicator, Eastings, Northings, ...
 * (Doc/Code-Point_Open_Column_Headers.csv). Quality 90 means "no coordinates": those rows are skipped.
 */
public final class CodePointReader {

    private static final int NO_COORDINATES = 90;

    private CodePointReader() {}

    /** @return normalised postcode ("SW11 1AA") -> WGS84 centroid, for the given areas ("sw", "se") */
    public static Map<String, GeoPoint> read(Path zip, List<String> areas) throws IOException {
        Map<String, GeoPoint> centroids = new HashMap<>();
        try (ZipFile z = new ZipFile(zip.toFile())) {   // try-with-resources: closed at the end, like Python's "with"
            for (String area : areas) {
                ZipEntry entry = z.getEntry("Data/CSV/" + area.toLowerCase() + ".csv");
                if (entry == null) {
                    throw new IOException("Code-Point zip has no Data/CSV/" + area + ".csv");
                }
                try (BufferedReader in = new BufferedReader(new InputStreamReader(z.getInputStream(entry), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        List<String> f = CsvLine.split(line);
                        if (f.size() < 4 || Integer.parseInt(f.get(1).trim()) == NO_COORDINATES) {
                            continue;
                        }
                        double easting = Double.parseDouble(f.get(2).trim());
                        double northing = Double.parseDouble(f.get(3).trim());
                        if (easting == 0 && northing == 0) {
                            continue;
                        }
                        centroids.put(Postcodes.normalise(f.get(0)), OsgbToWgs84.convert(easting, northing));
                    }
                }
            }
        }
        return centroids;
    }
}
