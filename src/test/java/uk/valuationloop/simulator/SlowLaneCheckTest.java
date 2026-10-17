package uk.valuationloop.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.valuationloop.simulator.SlowLaneCheck.Result;
import uk.valuationloop.simulator.SlowLaneCheck.Timing;

class SlowLaneCheckTest {

    // 6 partitions, 3 consumers, 2 each, as the broker reports them
    static final Map<Integer, String> OWNERS = Map.of(0, "c1", 1, "c1", 2, "c2", 3, "c2", 4, "c3", 5, "c3");

    @Test
    void okLeadsOnOtherThreadsDeliveredFirstPass() {
        Result r = SlowLaneCheck.evaluate(List.of(
                new Timing("ok", 3, 276), new Timing("slow", 1, 2281), new Timing("ok", 5, 278)), OWNERS);
        assertThat(r).isEqualTo(new Result(true, 0, 2));
    }

    @Test
    void anOkLeadSharingTheSlowThreadQueuesAndIsCountedNotFailed() {
        // the 2026-10-17 run: ok on p0 shares p1's consumer with the slow lead and waited for it
        Result r = SlowLaneCheck.evaluate(List.of(
                new Timing("ok", 3, 276), new Timing("slow", 1, 2281), new Timing("ok", 0, 2292)), OWNERS);
        assertThat(r).isEqualTo(new Result(true, 1, 1));
    }

    @Test
    void anOkLeadOnAnotherThreadThatWaitedFails() {
        Result r = SlowLaneCheck.evaluate(List.of(
                new Timing("ok", 3, 2300), new Timing("slow", 1, 2281), new Timing("ok", 5, 278)), OWNERS);
        assertThat(r.otherThreadsFirst()).isFalse();
    }
}
