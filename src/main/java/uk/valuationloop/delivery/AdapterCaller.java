package uk.valuationloop.delivery;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.valuationloop.delivery.CrmAdapter.CrmUnavailable;
import uk.valuationloop.delivery.CrmAdapter.LeadPush;

/**
 * Calls an adapter with a deadline (valuationloop.delivery.adapter-timeout-ms, default 5000). A CRM that does not
 * answer in time counts as a failed attempt: {@link AdapterTimeout} is a {@link CrmUnavailable}, so the record goes
 * to lead.deliver.v1-retry-0, -retry-1 and the DLT like any other failure (AdapterTimeoutTest).
 *
 * How: the call runs on a fresh virtual thread and the listener thread waits on its Future for at most the timeout.
 * On timeout, {@code cancel(true)} interrupts the virtual thread, so a sleeping or blocking-IO adapter stops instead
 * of running on in the background. Chosen over {@code CompletableFuture.orTimeout}: orTimeout only completes the
 * future exceptionally, it never interrupts the task, so a hung call would keep its thread (and its socket) forever.
 * A virtual thread per call costs about as much as an object allocation, so there is no pool to size.
 *
 * A timed-out call may still have reached the CRM. That is safe: the retry sends the same leadId, which the CRM
 * treats as the idempotency key (CrmAdapter#send).
 */
@Component
public class AdapterCaller {

    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    private final long timeoutMs;

    public AdapterCaller(@Value("${valuationloop.delivery.adapter-timeout-ms:5000}") long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public void send(CrmAdapter adapter, LeadPush push) {
        Map<String, String> logContext = MDC.getCopyOfContextMap();   // traceId lives in a thread-local; carry it over
        Future<?> call = calls.submit(() -> {
            if (logContext != null) {
                MDC.setContextMap(logContext);
            }
            adapter.send(push);
        });
        try {
            call.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);   // interrupt the call: the adapter's sleep or socket read ends now
            throw new AdapterTimeout(adapter.name(), push.leadId(), timeoutMs);
        } catch (ExecutionException e) {
            // the adapter threw: rethrow its own exception, so the listener (and the DLT reason) see CrmUnavailable etc.
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new CrmUnavailable("adapter '" + adapter.name() + "' failed: " + e.getCause());
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();   // the container is stopping: keep the flag for it
            throw new CrmUnavailable("interrupted while waiting for adapter '" + adapter.name() + "'");
        }
    }

    @PreDestroy
    void shutdown() {
        calls.shutdownNow();
    }

    /** The CRM did not answer within the timeout. Retryable, like any other unavailability. */
    public static class AdapterTimeout extends CrmUnavailable {
        public AdapterTimeout(String adapter, String leadId, long timeoutMs) {
            super("adapter '" + adapter + "' did not answer for lead " + leadId + " within " + timeoutMs + " ms");
        }
    }
}
