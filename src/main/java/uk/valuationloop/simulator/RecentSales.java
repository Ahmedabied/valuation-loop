package uk.valuationloop.simulator;

import java.util.List;
import uk.valuationloop.evidence.ComparableDocument;

/**
 * The demo's source for the "ok" scenario's property: real Land Registry sales from the comparables index, newest
 * first. An interface so SimulatorScenarioTest can seed one fictional sale; {@link EsRecentSales} reads the index.
 *
 * Why the ok request is a real sale: the Land Registry late match (completion module) can only find a completion that
 * is in the index, and the demo cannot wait weeks for a new one, so it asks about a flat that has already sold. The
 * match is then that same sale (a self-match, completion/package-info): it shows the join, not a discovery.
 */
public interface RecentSales {

    /**
     * Up to {@code max} sales in this outcode of this property type that have a floor area, most recent transfer first.
     * Throws {@link uk.valuationloop.evidence.ComparablesSearch.Unavailable} when the index cannot be reached.
     */
    List<ComparableDocument> latestWithFloorArea(String outcode, String propertyType, int max);
}
