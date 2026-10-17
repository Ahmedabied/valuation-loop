package uk.valuationloop.request;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import uk.valuationloop.outbox.UuidV7;
import uk.valuationloop.request.RequestTransitions.RequestEvent;

/**
 * The seller's request (one row). The Django analogue is a model with a status field, except the allowed status
 * changes live in RequestTransitions and nothing else can set the state.
 */
@Entity
@Table(name = "valuation_request")
public class ValuationRequest {

    @Id
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestState state;

    @Column(name = "seller_ref", nullable = false)
    private String sellerRef;

    @Column(nullable = false)
    private String postcode;

    @Column(nullable = false)
    private String outcode;

    @Enumerated(EnumType.STRING)
    @Column(name = "property_type", nullable = false)
    private PropertyType propertyType;

    @Column(nullable = false)
    private int bedrooms;

    @Column(name = "floor_area_m2")
    private BigDecimal floorAreaM2;

    @Column(name = "selected_lead_id")
    private String selectedLeadId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ValuationRequest() {} // JPA

    public static ValuationRequest receive(String sellerRef, String postcode, PropertyType propertyType, int bedrooms,
                                           BigDecimal floorAreaM2, Instant now) {
        ValuationRequest r = new ValuationRequest();
        r.id = UuidV7.prefixed("vr");
        r.state = RequestState.RECEIVED;
        r.sellerRef = sellerRef;
        r.postcode = Postcodes.normalise(postcode);
        r.outcode = Postcodes.outcode(r.postcode);
        r.propertyType = propertyType;
        r.bedrooms = bedrooms;
        r.floorAreaM2 = floorAreaM2;
        r.createdAt = now;
        r.updatedAt = now;
        return r;
    }

    public void apply(RequestEvent event, Instant now) {
        this.state = RequestTransitions.next(this.state, event);
        this.updatedAt = now;
    }

    public void select(String leadId, Instant now) {
        apply(new RequestEvent.SellerSelected(), now);
        this.selectedLeadId = leadId;
    }

    public String getId() { return id; }
    public RequestState getState() { return state; }
    public String getSellerRef() { return sellerRef; }
    public String getPostcode() { return postcode; }
    public String getOutcode() { return outcode; }
    public PropertyType getPropertyType() { return propertyType; }
    public int getBedrooms() { return bedrooms; }
    public BigDecimal getFloorAreaM2() { return floorAreaM2; }
    public String getSelectedLeadId() { return selectedLeadId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
