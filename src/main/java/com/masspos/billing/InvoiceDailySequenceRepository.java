package com.masspos.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceDailySequenceRepository extends JpaRepository<InvoiceDailySequence, UUID> {

    Optional<InvoiceDailySequence> findByTerminalCodeAndBusinessDate(String terminalCode, LocalDate businessDate);
}
