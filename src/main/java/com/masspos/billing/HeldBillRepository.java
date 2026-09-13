package com.masspos.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface HeldBillRepository extends JpaRepository<HeldBill, UUID> {

    List<HeldBill> findByTerminalCodeOrderByCreatedAtDesc(String terminalCode);
}
