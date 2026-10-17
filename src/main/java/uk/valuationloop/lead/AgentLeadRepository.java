package uk.valuationloop.lead;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentLeadRepository extends JpaRepository<AgentLead, String> {

    List<AgentLead> findByRequestIdOrderByCreatedAt(String requestId);

    Optional<AgentLead> findByRequestIdAndAgentId(String requestId, String agentId);

    /** Leads whose reply window has closed; the deadline scanner expires them. */
    List<AgentLead> findByStateAndReplyDueAtBefore(LeadState state, Instant cutoff);
}
