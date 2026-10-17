package uk.valuationloop.evidence;

import java.util.List;

/**
 * An evidence pack exactly as served: this record, serialised, is what evidence_pack.pack_json holds and what the
 * seller's comparison view shows. It describes comparables; it never scores or ranks the agent who attached it.
 *
 * @param comparablesRange null when fewer than 3 comparables remain; {@code rangeNote} then says so in words
 * @param method           how comparablesRange is computed, in words
 */
public record Pack(
        String packId,
        List<Comparable> comparables,
        ComparablesRange comparablesRange,
        String rangeNote,
        String dataVintage,
        String method,
        Provenance provenance) {

    public static final String NO_RANGE_NOTE = "fewer than " + ComparablesRange.MINIMUM_COMPARABLES
            + " comparables: no range is shown";
}
