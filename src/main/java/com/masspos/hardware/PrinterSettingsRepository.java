package com.masspos.hardware;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PrinterSettingsRepository extends JpaRepository<PrinterSettings, UUID> {

    Optional<PrinterSettings> findBySingleton(int singleton);
}
