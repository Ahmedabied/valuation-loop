package uk.valuationloop.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import uk.valuationloop.delivery.CrmAdapter.CrmUnavailable;
import uk.valuationloop.delivery.CrmAdapter.LeadPush;

/** Plain unit tests: no Spring, no Kafka. */
class AdapterRegistryTest {

    final FakeCrm crm = new FakeCrm();
    final AdapterRegistry registry = new AdapterRegistry(List.of(
            new OkAdapter(crm), new SlowAdapter(crm, 1), new FlakyAdapter(crm, 2)));

    static LeadPush push(String leadId) {
        return new LeadPush(leadId, "vr_test", "ag_fictional_01");
    }

    @Test
    void findsEachAdapterByItsPayloadName() {
        assertThat(registry.forName("ok")).isInstanceOf(OkAdapter.class);
        assertThat(registry.forName("slow")).isInstanceOf(SlowAdapter.class);
        assertThat(registry.forName("flaky")).isInstanceOf(FlakyAdapter.class);
    }

    @Test
    void unknownNameIsANonRetryableError() {
        assertThatThrownBy(() -> registry.forName("carrier-pigeon"))
                .isInstanceOf(AdapterRegistry.UnknownAdapter.class)
                .hasMessageContaining("carrier-pigeon");
    }

    @Test
    void twoAdaptersWithTheSameNameIsAWiringBug() {
        assertThatThrownBy(() -> new AdapterRegistry(List.of(new OkAdapter(crm), new OkAdapter(crm))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void flakyFailsTheFirstTwoCallsPerLeadThenAccepts() {
        CrmAdapter flaky = registry.forName("flaky");
        assertThatThrownBy(() -> flaky.send(push("ld_a"))).isInstanceOf(CrmUnavailable.class);
        assertThatThrownBy(() -> flaky.send(push("ld_a"))).isInstanceOf(CrmUnavailable.class);
        flaky.send(push("ld_a"));                                    // third try: the default demo lands here
        assertThat(crm.received()).containsExactly("ld_a");

        // another lead has its own count: ld_a's failures did not use up ld_b's
        assertThatThrownBy(() -> flaky.send(push("ld_b"))).isInstanceOf(CrmUnavailable.class);
        assertThat(crm.received()).doesNotContain("ld_b");
    }

    @Test
    void fakeCrmDedupesOnLeadIdButCountsCalls() {
        CrmAdapter ok = registry.forName("ok");
        ok.send(push("ld_c"));
        ok.send(push("ld_c"));
        assertThat(crm.received()).containsExactly("ld_c");
        assertThat(crm.acceptedCalls("ld_c")).isEqualTo(2);
        assertThat(crm.acceptedCalls("ld_never")).isZero();
    }

    @Test
    void slowAcceptsAfterItsDelay() {
        registry.forName("slow").send(push("ld_d"));
        assertThat(crm.received()).contains("ld_d");
    }
}
