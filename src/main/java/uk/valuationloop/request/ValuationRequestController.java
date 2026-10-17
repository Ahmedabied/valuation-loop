package uk.valuationloop.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.valuationloop.lead.AgentLead;
import uk.valuationloop.lead.AgentLeadRepository;

/**
 * The seller-facing entry point. Django: a view + serializer; here the records are the serializers.
 * 422 for a bad postcode, an unknown selected agent or no slot holder; error bodies come from
 * {@link uk.valuationloop.api.ApiErrors}.
 */
@RestController
@RequestMapping("/valuation-requests")
public class ValuationRequestController {

    public record CreateRequest(
            @NotBlank String sellerRef,
            @NotBlank String postcode,
            @NotNull PropertyType propertyType,
            @Min(0) @Max(20) int bedrooms,
            BigDecimal floorAreaM2,
            List<String> selectedAgentIds) {}

    public record LeadSummary(String leadId, String agentId, String state) {
        static LeadSummary of(AgentLead l) { return new LeadSummary(l.getId(), l.getAgentId(), l.getState().name()); }
    }

    public record RequestView(String requestId, String state, String postcode, String outcode, String propertyType,
                              int bedrooms, List<LeadSummary> leads) {}

    private final ValuationRequestService service;
    private final ValuationRequestRepository requests;
    private final AgentLeadRepository leads;

    public ValuationRequestController(ValuationRequestService service, ValuationRequestRepository requests,
                                      AgentLeadRepository leads) {
        this.service = service;
        this.requests = requests;
        this.leads = leads;
    }

    @PostMapping
    public ResponseEntity<RequestView> create(@Valid @RequestBody CreateRequest body) {
        var created = service.create(new ValuationRequestService.CreateCommand(body.sellerRef(), body.postcode(),
                body.propertyType(), body.bedrooms(), body.floorAreaM2(), body.selectedAgentIds()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(created.request(), created.leads()));
    }

    @GetMapping("/{id}")
    public RequestView get(@PathVariable String id) {
        ValuationRequest r = requests.findById(id)
                .orElseThrow(() -> new RequestNotFound(id));
        return view(r, leads.findByRequestIdOrderByCreatedAt(id));
    }

    private static RequestView view(ValuationRequest r, List<AgentLead> ls) {
        return new RequestView(r.getId(), r.getState().name(), r.getPostcode(), r.getOutcode(),
                r.getPropertyType().name(), r.getBedrooms(), ls.stream().map(LeadSummary::of).toList());
    }

    /** 404 (mapped in {@link uk.valuationloop.api.ApiErrors}). */
    public static class RequestNotFound extends RuntimeException {
        public RequestNotFound(String requestId) { super("no request " + requestId); }
    }
}
