package com.masspos.terminal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TerminalRepository extends JpaRepository<Terminal, UUID> {

    Optional<Terminal> findByCode(String code);

    List<Terminal> findAllByOrderBySectionAscCodeAsc();
}
