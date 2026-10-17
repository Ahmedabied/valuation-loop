package uk.valuationloop.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;

/**
 * The tracing wiring the envelopes depend on: Spring Boot gives the app a real OpenTelemetry tracer (not the no-op),
 * a new trace yields a W3C traceparent for the envelope, and a consumer that opens a span from that traceparent
 * carries the same traceId. Guards the Boot 4 move, where tracing auto-configuration became its own module.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1)
class EventTracingTest {

    @Autowired Tracer tracer;

    @Test
    void otelTracerIsWiredAndTheTraceparentCarriesTheTraceIdAcrossAHop() {
        assertThat(tracer).isInstanceOf(OtelTracer.class);

        String traceparent = EventTracing.newTrace("test.produce", EventTracing::currentTraceparent);
        assertThat(traceparent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");

        String consumerTraceId = EventTracing.inTrace(traceparent, "test.consume", EventTracing::currentTraceId);
        assertThat(consumerTraceId).isEqualTo(traceparent.substring(3, 35));
    }
}
