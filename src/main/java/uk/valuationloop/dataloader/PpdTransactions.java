package uk.valuationloop.dataloader;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The set of sales to index, built by replaying Price Paid rows in file order (oldest file first).
 *
 * <pre>
 * status A (added) or C (changed): if the row passes the filter, it is the current version of that transaction
 *                                  (a C replaces the earlier A); if it no longer passes (say a C moved it to
 *                                  category B), any earlier version is dropped
 * status D (deleted):              drop the transaction, whatever it was
 * filter:                          district in the configured set, category A (standard price paid), property type
 *                                  not O (other), postcode present
 * </pre>
 * The yearly files on disk carry only status A today (C and D appear in the monthly change files), but the rule is
 * here so a monthly file can be appended to loader.ppd-files without code changes.
 */
public final class PpdTransactions {

    private final Set<String> districts;
    private final Map<String, PpdRow> current = new LinkedHashMap<>();
    private long rowsSeen;

    public PpdTransactions(Set<String> districts) {
        this.districts = districts;
    }

    public void apply(PpdRow row) {
        rowsSeen++;
        if ("D".equals(row.recordStatus())) {
            current.remove(row.transactionId());
        } else if (keep(row)) {
            current.put(row.transactionId(), row);
        } else {
            current.remove(row.transactionId());
        }
    }

    boolean keep(PpdRow row) {
        return districts.contains(row.district())
                && "A".equals(row.category())
                && !"O".equals(row.propertyType())
                && !row.postcode().isBlank();
    }

    public Collection<PpdRow> rows() {
        return current.values();
    }

    public int size() {
        return current.size();
    }

    public long rowsSeen() {
        return rowsSeen;
    }
}
