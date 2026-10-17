package uk.valuationloop.dataloader;

import java.time.LocalDate;

/**
 * One line of HM Land Registry Price Paid Data (OGL v3.0). The yearly files have no header; the 16 columns are, in
 * order: transaction id ({GUID}), price, date of transfer, postcode, property type (D/S/T/F/O), old/new (Y = new
 * build), duration (F freehold / L leasehold), PAON, SAON, street, locality, town, district, county,
 * PPD category (A standard / B additional), record status (A added / C changed / D deleted).
 *
 * PAON, SAON and street are used only to build the in-memory EPC join key; they are never indexed.
 */
public record PpdRow(
        String transactionId,
        long price,
        LocalDate dateOfTransfer,
        String postcode,
        String propertyType,
        boolean newBuild,
        String tenure,
        String paon,
        String saon,
        String street,
        String district,
        String category,
        String recordStatus) {
}
