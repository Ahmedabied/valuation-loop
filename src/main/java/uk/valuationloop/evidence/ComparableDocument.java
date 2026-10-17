package uk.valuationloop.evidence;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One document in the comparables index: one Land Registry sale, enriched with EPC-derived attributes when the
 * loader could match the address. The mapping is src/main/resources/es/comparables-v1.json.
 *
 * Fields that came from EPC (floorAreaM2, epcBand, builtForm, constructionAgeBand, habitableRooms) are null when
 * there was no match, and NON_NULL leaves them OUT of the JSON instead of writing a sentinel. That matters: the
 * comparables query asks "does this document have a floor area?" with an exists query (H2-review E2-7).
 *
 * No address is stored: no PAON, SAON, street, and nothing at all from the EPC address columns.
 * dateOfTransfer is an ISO date string ("2025-03-14") so the document needs no Jackson date module.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ComparableDocument(
        String ppdTransactionId,
        long price,
        String dateOfTransfer,
        String postcode,
        String outcode,
        GeoPoint location,
        String propertyType,
        String builtForm,
        boolean newBuild,
        String tenure,
        Double floorAreaM2,
        String epcBand,
        String constructionAgeBand,
        Integer habitableRooms,
        String district,
        String dataVintage) {
}
