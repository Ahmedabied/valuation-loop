package uk.valuationloop.request;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ValuationRequestRepository extends JpaRepository<ValuationRequest, String> {
}
