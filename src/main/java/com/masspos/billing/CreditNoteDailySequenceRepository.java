package com.masspos.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface CreditNoteDailySequenceRepository extends JpaRepository<CreditNoteDailySequence, UUID> {

    Optional<CreditNoteDailySequence> findByTerminalCodeAndBusinessDate(String terminalCode, LocalDate businessDate);
}
