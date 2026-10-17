package uk.valuationloop.evidence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EvidencePackExclusionRepository extends JpaRepository<EvidencePackExclusion, EvidencePackExclusion.Key> {

    /** JPQL (queries entities, not tables): the excluded transaction ids of one pack, oldest first. */
    @Query("select e.id.ppdTransactionId from EvidencePackExclusion e where e.id.packId = :packId order by e.excludedAt")
    List<String> excludedIds(@Param("packId") String packId);
}
