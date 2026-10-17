package uk.valuationloop.evidence;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the comparables query, bound from valuationloop.evidence.* (Django: a settings dict with defaults).
 * Spring fills the record from config; @DefaultValue is used when the key is absent, so application.yml needs
 * nothing for the defaults to apply.
 *
 * @param index                  comparables index the loader writes and the pack builder reads
 * @param centroidIndex          postcode -> lat/lon index (Code-Point Open centroids, SW and SE areas)
 * @param radiusM                hard filter: only sales within this distance of the request postcode
 * @param maxAgeMonths           hard filter: only sales in the last N months
 * @param size                   how many comparables a pack holds at most
 * @param locationScaleM         gauss scale on distance: a comparable this far away scores 0.5 on location
 * @param dateScaleDays          gauss scale on sale date: a sale this old scores 0.5 on recency
 * @param floorAreaScaleM2       gauss scale on floor area difference (after the offset)
 * @param floorAreaOffsetM2      floor area differences up to this many m2 count as a perfect match
 * @param missingFloorAreaWeight weight for a comparable with no floor area, so it ranks below an equal one with one
 */
@ConfigurationProperties("valuationloop.evidence")
public record EvidenceProperties(
        @DefaultValue("comparables-v1") String index,
        @DefaultValue("postcode-centroids-v1") String centroidIndex,
        @DefaultValue("1500") int radiusM,
        @DefaultValue("24") int maxAgeMonths,
        @DefaultValue("10") int size,
        @DefaultValue("400") int locationScaleM,
        @DefaultValue("180") int dateScaleDays,
        @DefaultValue("15") double floorAreaScaleM2,
        @DefaultValue("5") double floorAreaOffsetM2,
        @DefaultValue("0.5") double missingFloorAreaWeight) {

    /** The defaults, for unit tests that build a query without a Spring context. */
    public static EvidenceProperties defaults() {
        return new EvidenceProperties("comparables-v1", "postcode-centroids-v1", 1500, 24, 10, 400, 180, 15, 5, 0.5);
    }
}
