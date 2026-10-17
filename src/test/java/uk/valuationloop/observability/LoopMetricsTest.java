package uk.valuationloop.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

/** The metrics register under the names the README and `make demo` grep for. */
class LoopMetricsTest {

    @Test
    void registersOutboxUnsentDltRecordsReplyMinutesAndScorecardSkippedForPrometheus() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        LoopMetrics metrics = new LoopMetrics(registry);

        metrics.outboxUnsent(4);
        metrics.dltRecord();
        metrics.replyMinutes(12.5);
        metrics.replyMinutes(7.5);
        metrics.scorecardSkipped();

        assertThat(registry.get(LoopMetrics.OUTBOX_UNSENT).gauge().value()).isEqualTo(4.0);
        assertThat(metrics.dltRecordsSoFar()).isEqualTo(1.0);
        assertThat(registry.get(LoopMetrics.REPLY_MINUTES).summary().totalAmount()).isEqualTo(20.0);

        String scrape = registry.scrape();
        assertThat(scrape).contains("outbox_unsent 4.0")
                .contains("dlt_records_total 1.0")
                .contains("reply_minutes_count 2")
                .contains("reply_minutes_sum 20.0")
                .contains("scorecard_skipped_total 1.0");
    }
}
