package com.masspos.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CreditNoteSequenceRepository extends JpaRepository<CreditNoteSequence, UUID> {

    Optional<CreditNoteSequence> findByTerminalCodeAndFinancialYear(String terminalCode, String financialYear);
}
