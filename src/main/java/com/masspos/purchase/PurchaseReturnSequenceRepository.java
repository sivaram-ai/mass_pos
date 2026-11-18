package com.masspos.purchase;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PurchaseReturnSequenceRepository extends JpaRepository<PurchaseReturnSequence, UUID> {

    Optional<PurchaseReturnSequence> findByFinancialYear(String financialYear);
}
