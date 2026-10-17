package uk.valuationloop.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.valuationloop.outbox.Topics;

/**
 * H2-review E2-3's guard: with the relay's scheduler on (fixedDelay 300 ms), an event is on the topic within
 * one second of the API call returning. If someone slows the relay or breaks its draining loop, this fails.
 */
class OutboxLatencyTest extends DeliveryIntegrationTestBase {

    @Test
    void firstEventIsOnTheTopicWithinOneSecondOfTheApiCall() throws Exception {
        try (KafkaTail tail = KafkaTail.fromEnd(broker, Topics.VALUATION_REQUESTED)) {
            String requestId = postRequest("seller-latency-1", "SW4 7AA", List.of());
            long returnedAt = System.nanoTime();

            await().atMost(Duration.ofSeconds(1)).pollInterval(Duration.ofMillis(20))
                    .until(() -> !tail.poll(r -> requestId.equals(r.key())).isEmpty());
            long millis = Duration.ofNanos(System.nanoTime() - returnedAt).toMillis();
            assertThat(millis).isLessThan(1000);
        }
    }
}
