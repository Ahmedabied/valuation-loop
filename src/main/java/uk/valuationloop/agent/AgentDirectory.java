package uk.valuationloop.agent;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The agents that hold a slot for an outcode. Fixtures only: every agent here is fictional.
 *
 * This is an ELIGIBILITY LOOKUP, not a ranking. Which agents may receive a request is a commercial fact
 * (who holds the slot) or the seller's own choice; nothing here orders agents by behaviour (ARCHITECTURE.md §0).
 */
@Component
public class AgentDirectory {

    public record Agent(String id, String name, Set<String> outcodes, String adapter) {}

    private static final List<Agent> FIXTURES = List.of(
            new Agent("ag_fictional_01", "Alder & Finch",            Set.of("SW11", "SW8"),          "ok"),
            new Agent("ag_fictional_02", "Bramble Lettings",         Set.of("SW4", "SW11", "SW12"),  "slow"),
            new Agent("ag_fictional_03", "Cobalt Property Co",       Set.of("SW11", "SW12", "SW17"), "flaky"),
            new Agent("ag_fictional_04", "Dunmore Estates",          Set.of("SW2", "SW9", "SE24"),   "ok"),
            new Agent("ag_fictional_05", "Elm Row Homes",            Set.of("SW18", "SW15", "SW11"), "ok"),
            new Agent("ag_fictional_06", "Fennel Sales",             Set.of("SE11", "SE1", "SW8"),   "slow"));

    public List<Agent> eligibleFor(String outcode) {
        return FIXTURES.stream().filter(a -> a.outcodes().contains(outcode)).toList();
    }

    public Optional<Agent> byId(String agentId) {
        return FIXTURES.stream().filter(a -> a.id().equals(agentId)).findFirst();
    }

    public List<Agent> all() {
        return FIXTURES;
    }
}
