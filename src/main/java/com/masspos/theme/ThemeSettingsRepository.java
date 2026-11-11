package com.masspos.theme;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ThemeSettingsRepository extends JpaRepository<ThemeSettings, UUID> {

    Optional<ThemeSettings> findBySingleton(int singleton);
}
