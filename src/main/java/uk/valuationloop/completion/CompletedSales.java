package uk.valuationloop.completion;

import java.time.LocalDate;
import java.util.List;
import uk.valuationloop.evidence.ComparableDocument;

/**
 * Land Registry sales that could be the completion of an instructed lead. An interface (a Python Protocol) so the
 * scanner can be tested with an in-memory fake; {@link EsCompletedSales} reads the comparables index the loader
 * already fills with Price Paid rows (ComparableDocument is that index's document).
 *
 * Why not the evidence module's ComparablesSearch: that query is a ranked top-N within a radius of a centroid, built
 * to find SIMILAR sales. A completion is one exact sale ("this postcode, this type, on or after this date"), so it
 * gets an exact filter query of its own; ranked results could push the right sale out of the top N.
 */
public interface CompletedSales {

    /**
     * Sales at exactly this postcode and property type, transferred on or after {@code onOrAfter}, earliest first.
     * Throws {@link uk.valuationloop.evidence.ComparablesSearch.Unavailable} when the index cannot be reached.
     */
    List<ComparableDocument> find(String postcode, String propertyType, LocalDate onOrAfter);
}
