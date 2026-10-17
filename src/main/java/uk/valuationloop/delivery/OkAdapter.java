package uk.valuationloop.delivery;

import org.springframework.stereotype.Component;

/** A CRM that always accepts, immediately. */
@Component
public class OkAdapter implements CrmAdapter {

    private final FakeCrm crm;

    public OkAdapter(FakeCrm crm) {
        this.crm = crm;
    }

    @Override
    public String name() {
        return "ok";
    }

    @Override
    public void send(LeadPush push) {
        crm.accept(push.leadId());
    }
}
