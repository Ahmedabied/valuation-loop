package uk.valuationloop.scorecard;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScorecardFactRepository extends JpaRepository<ScorecardFact, String> {

    /** SELECT ... FOR UPDATE: the row stays locked until the projector's transaction commits. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from ScorecardFact f where f.leadId = :leadId")
    Optional<ScorecardFact> findForUpdate(@Param("leadId") String leadId);

    /** Every fact of one request: the range ranks are computed across them. */
    List<ScorecardFact> findByRequestId(String requestId);

    /** One agent's own facts. The scorecard API never reads another agent's rows. */
    List<ScorecardFact> findByAgentId(String agentId);

    List<ScorecardFact> findAllByOrderByLeadId();
}
