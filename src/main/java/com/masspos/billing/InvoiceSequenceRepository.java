package com.masspos.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InvoiceSequenceRepository extends JpaRepository<InvoiceSequence, UUID> {

    Optional<InvoiceSequence> findByTerminalCodeAndFinancialYear(String terminalCode, String financialYear);
}
