package uk.valuationloop.evidence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidencePackRepository extends JpaRepository<EvidencePack, String> {

    /** Spring Data derives the query from the name: "is any pack of this lead already attached?" */
    boolean existsByLeadIdAndAttachedAtIsNotNull(String leadId);
}
