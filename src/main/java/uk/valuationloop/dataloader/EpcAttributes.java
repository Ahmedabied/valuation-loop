package uk.valuationloop.dataloader;

import java.time.LocalDate;

/**
 * The only things kept from an EPC certificate: physical attributes of the dwelling (OGL fields). No address,
 * no certificate number, no UPRN. lodgementDate is kept only to pick the latest certificate per address.
 *
 * @param propertyType EPC's own word (Flat, House, Maisonette, Bungalow); used to refine PPD's coarser type
 */
public record EpcAttributes(
        Double floorAreaM2,
        String epcBand,
        String builtForm,
        String constructionAgeBand,
        Integer habitableRooms,
        String propertyType,
        LocalDate lodgementDate) {

    public boolean isNewerThan(EpcAttributes other) {
        return other == null || lodgementDate.isAfter(other.lodgementDate);
    }
}
