package uk.valuationloop.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Carries a trace across the outbox and Kafka by value: the W3C {@code traceparent} string
 * ("00-{32 hex traceId}-{16 hex spanId}-{flags}") goes into the event envelope when the event is written, and
 * whoever handles the event later (the relay, a consumer) opens a child span of it, so their log lines carry the
 * same traceId as the API request that caused them.
 *
 * Why by value and not with Kafka header propagation alone: the outbox relay sends on a scheduler thread, hundreds
 * of milliseconds after the request finished, so at send time there is no request span to propagate. The envelope
 * remembers it.
 *
 * The tracer is held in a static field because {@link uk.valuationloop.outbox.EventEnvelope#of} is a static factory
 * called from every module. Spring sets it once at startup; until then (and in tests, where Boot switches tracing
 * off) it is {@link Tracer#NOOP}: no current trace, traceparent null, handlers run without a span. Nothing breaks.
 */
@Component
public class EventTracing {

    private static final Pattern TRACEPARENT = Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    private static volatile Tracer tracer = Tracer.NOOP;

    public EventTracing(ObjectProvider<Tracer> tracerProvider) {
        tracer = tracerProvider.getIfAvailable(() -> Tracer.NOOP);
    }

    /** The current span as a traceparent, or null when no span is active (no request, or tracing off). */
    public static String currentTraceparent() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        return "00-" + context.traceId() + "-" + context.spanId() + "-" + (Boolean.TRUE.equals(context.sampled()) ? "01" : "00");
    }

    /** The traceId of the current span, or null. Used by the simulator to print which trace to grep for. */
    public static String currentTraceId() {
        TraceContext context = tracer.currentTraceContext().context();
        return context == null ? null : context.traceId();
    }

    /**
     * Runs {@code work} inside a new span whose parent is {@code traceparent}, so logging inside it shows that
     * trace's id (Micrometer puts traceId/spanId into the SLF4J MDC while the span is in scope). With a missing or
     * malformed traceparent the work simply runs as it is.
     */
    public static <T> T inTrace(String traceparent, String spanName, Supplier<T> work) {
        Matcher m = traceparent == null ? null : TRACEPARENT.matcher(traceparent);
        if (m == null || !m.matches()) {
            return work.get();
        }
        TraceContext parent = tracer.traceContextBuilder()
                .traceId(m.group(1)).spanId(m.group(2)).sampled("01".equals(m.group(3))).build();
        Span span = tracer.spanBuilder().setParent(parent).name(spanName).start();
        return runInSpan(span, work);
    }

    public static void inTrace(String traceparent, String spanName, Runnable work) {
        inTrace(traceparent, spanName, () -> {
            work.run();
            return null;
        });
    }

    /** Runs {@code work} as the root of a brand-new trace (the simulator gives each scenario its own traceId). */
    public static <T> T newTrace(String spanName, Supplier<T> work) {
        try (Tracer.SpanInScope noParent = tracer.withSpan(null)) {   // detach from the HTTP request's span
            return runInSpan(tracer.nextSpan().name(spanName).start(), work);
        }
    }

    private static <T> T runInSpan(Span span, Supplier<T> work) {
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            return work.get();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
