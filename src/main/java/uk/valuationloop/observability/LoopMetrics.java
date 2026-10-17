package uk.valuationloop.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * The three Prometheus metrics of ARCHITECTURE.md §11, registered in one place so their names live in one file.
 * Micrometer names use dots; the Prometheus endpoint turns them into the names below (a counter gets "_total").
 *
 * <pre>
 *   outbox_unsent        gauge    rows committed but not yet on Kafka; sampled by OutboxRelay every tick
 *   dlt_records_total    counter  DeliverLead records that reached lead.deliver.v1-dlt (LeadDeliveryListener)
 *   reply_minutes        summary  minutes from delivery to the agent's reply (_count, _sum, _max), ReplyService
 * </pre>
 * Labels are deliberately absent: no agentId label, so no metric can be read as a per-agent league table.
 */
@Component
public class LoopMetrics {

    public static final String OUTBOX_UNSENT = "outbox.unsent";
    public static final String DLT_RECORDS = "dlt.records";
    public static final String REPLY_MINUTES = "reply.minutes";

    private final AtomicLong outboxUnsent = new AtomicLong();   // the gauge reads this value when scraped
    private final Counter dltRecords;
    private final DistributionSummary replyMinutes;

    public LoopMetrics(MeterRegistry registry) {
        Gauge.builder(OUTBOX_UNSENT, outboxUnsent, AtomicLong::get)
                .description("Outbox rows committed but not yet sent to Kafka (sampled every relay tick)")
                .register(registry);
        this.dltRecords = Counter.builder(DLT_RECORDS)
                .description("DeliverLead records that reached the dead letter topic")
                .register(registry);
        this.replyMinutes = DistributionSummary.builder(REPLY_MINUTES)
                .baseUnit("minutes")
                .description("Minutes from lead delivery to the agent's reply")
                .register(registry);
    }

    public void outboxUnsent(long rows) {
        outboxUnsent.set(rows);
    }

    public void dltRecord() {
        dltRecords.increment();
    }

    public void replyMinutes(double minutes) {
        replyMinutes.record(minutes);
    }

    /** Read back by the simulator's flaky-crm report. */
    public double dltRecordsSoFar() {
        return dltRecords.count();
    }
}
