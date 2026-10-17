package uk.valuationloop.simulator;

import java.util.List;
import java.util.Map;

/**
 * The slow scenario's verdict on its own delivery times, as a pure function so it has a unit test.
 *
 * What phase 2 promises: a slow CRM holds ONE listener thread. lead.deliver.v1 has 6 partitions and 3 threads, so each
 * thread (one consumer in group "delivery") owns 2 partitions, and a lead keyed onto a partition owned by the slow
 * lead's thread still queues behind it, by design. So the check is: every ok lead owned by ANOTHER consumer was
 * delivered before the slow one; ok leads that share the slow lead's consumer are counted and shown, not hidden.
 * Which consumer owns which partition comes from the broker (DeliveryControl.deliveryOwners), not from a formula.
 */
final class SlowLaneCheck {

    /** One delivered lead: its adapter, its lead.deliver.v1 partition and how long delivery took. */
    record Timing(String adapter, int partition, long deliveryMs) {}

    /**
     * @param otherThreadsFirst every ok lead on another consumer than the slow lead finished before the slow lead
     * @param sameThread        ok leads on the slow lead's consumer (they queue behind it)
     * @param otherThreads      ok leads on other consumers
     */
    record Result(boolean otherThreadsFirst, int sameThread, int otherThreads) {}

    private SlowLaneCheck() {
    }

    static Result evaluate(List<Timing> leads, Map<Integer, String> ownerByPartition) {
        Timing slow = leads.stream().filter(t -> t.adapter().equals("slow")).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no slow lead"));
        String slowOwner = ownerByPartition.get(slow.partition());
        boolean first = true;
        int same = 0;
        int other = 0;
        for (Timing t : leads) {
            if (t == slow) {
                continue;
            }
            String owner = ownerByPartition.get(t.partition());
            if (owner != null && owner.equals(slowOwner)) {
                same++;
            } else {
                other++;
                first &= t.deliveryMs() < slow.deliveryMs();
            }
        }
        return new Result(first, same, other);
    }
}
