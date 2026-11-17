package com.masspos.purchase;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PurchaseOrderSequenceRepository extends JpaRepository<PurchaseOrderSequence, UUID> {

    Optional<PurchaseOrderSequence> findByFinancialYear(String financialYear);
}
