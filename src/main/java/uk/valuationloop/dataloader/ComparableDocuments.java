package uk.valuationloop.dataloader;

import uk.valuationloop.evidence.ComparableDocument;
import uk.valuationloop.evidence.GeoPoint;
import uk.valuationloop.request.Postcodes;

/**
 * PPD sale + (maybe) EPC attributes + postcode centroid -> one {@link ComparableDocument}.
 *
 * Property type: PPD says D, S, T (detached / semi / terraced: all HOUSE) or F (FLAT); O never gets here.
 * A matched EPC refines it within the same family only: a PPD flat that EPC calls a Maisonette becomes MAISONETTE,
 * a PPD house that EPC calls a Bungalow becomes BUNGALOW. Anything else keeps the PPD type, because PPD is the
 * record of the sale itself.
 */
public final class ComparableDocuments {

    private ComparableDocuments() {}

    public static ComparableDocument from(PpdRow sale, EpcAttributes epc, GeoPoint centroid, String dataVintage) {
        String postcode = Postcodes.normalise(sale.postcode());
        return new ComparableDocument(
                sale.transactionId(),
                sale.price(),
                sale.dateOfTransfer().toString(),
                postcode,
                Postcodes.outcode(postcode),
                centroid,
                propertyType(sale.propertyType(), epc == null ? null : epc.propertyType()),
                epc == null ? null : epc.builtForm(),
                sale.newBuild(),
                sale.tenure(),
                epc == null ? null : epc.floorAreaM2(),
                epc == null ? null : epc.epcBand(),
                epc == null ? null : epc.constructionAgeBand(),
                epc == null ? null : epc.habitableRooms(),
                sale.district(),
                dataVintage);
    }

    /** @param epcType null when there is no EPC match */
    static String propertyType(String ppdType, String epcType) {
        String base = switch (ppdType) {
            case "D", "S", "T" -> "HOUSE";
            case "F" -> "FLAT";
            default -> throw new IllegalArgumentException("PPD property type " + ppdType + " is not indexed");
        };
        if ("FLAT".equals(base) && "Maisonette".equalsIgnoreCase(epcType)) {
            return "MAISONETTE";
        }
        if ("HOUSE".equals(base) && "Bungalow".equalsIgnoreCase(epcType)) {
            return "BUNGALOW";
        }
        return base;
    }
}
