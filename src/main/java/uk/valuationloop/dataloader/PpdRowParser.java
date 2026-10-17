package uk.valuationloop.dataloader;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Parses one Price Paid line; a malformed line is Optional.empty() (counted by the loader, never fatal). */
public final class PpdRowParser {

    private static final int COLUMNS = 16;

    private PpdRowParser() {}

    public static Optional<PpdRow> parse(String line) {
        List<String> f = CsvLine.split(line);
        if (f.size() != COLUMNS) {
            return Optional.empty();
        }
        try {
            return Optional.of(new PpdRow(
                    stripBraces(f.get(0)),
                    Long.parseLong(f.get(1).trim()),
                    LocalDate.parse(f.get(2).trim().substring(0, 10)),   // "2025-03-14 00:00" -> 2025-03-14
                    f.get(3).trim().toUpperCase(),
                    f.get(4).trim(),
                    "Y".equals(f.get(5).trim()),
                    f.get(6).trim(),
                    f.get(7).trim(),
                    f.get(8).trim(),
                    f.get(9).trim(),
                    f.get(12).trim().toUpperCase(),
                    f.get(14).trim(),
                    f.get(15).trim()));
        } catch (RuntimeException e) {   // bad number or date: skip the line, the caller counts it
            return Optional.empty();
        }
    }

    /** "{1A2B...}" -> "1A2B..." */
    static String stripBraces(String id) {
        String t = id.trim();
        return t.startsWith("{") && t.endsWith("}") ? t.substring(1, t.length() - 1) : t;
    }
}
