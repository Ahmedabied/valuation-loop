package uk.valuationloop.dataloader;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits one CSV line into fields: commas separate, double quotes wrap a field that may contain commas, and "" inside
 * quotes is a literal quote. Enough for the three input files (none has a newline inside a field; checked by
 * comparing line counts with Python's csv reader). Python equivalent: next(csv.reader([line])).
 */
public final class CsvLine {

    private CsvLine() {}

    public static List<String> split(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else if (c == '"') {
                    inQuotes = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
