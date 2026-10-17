package uk.valuationloop.evidence;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One comparable sale as it appears in a served pack. Optional fields (floorAreaM2, epcBand, builtForm) are left
 * out of the JSON when unknown rather than shown as null or a made-up value.
 * No address: the seller sees price, date, distance and the property's attributes, and the Land Registry
 * transaction id, which anyone can look up in the public Price Paid data.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Comparable(
        String ppdTransactionId,
        long price,
        String date,
        long distanceM,
        Double floorAreaM2,
        String epcBand,
        String propertyType,
        String builtForm) {

    public static Comparable from(ComparableDocument doc, GeoPoint requestPoint) {
        return new Comparable(doc.ppdTransactionId(), doc.price(), doc.dateOfTransfer(),
                Math.round(requestPoint.distanceMetresTo(doc.location())),
                doc.floorAreaM2(), doc.epcBand(), doc.propertyType(), doc.builtForm());
    }
}
