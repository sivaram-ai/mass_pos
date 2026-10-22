package com.masspos.supplier;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    Optional<Supplier> findByCodeIgnoreCase(String code);

    List<Supplier> findByActiveTrueOrderByNameAsc();

    List<Supplier> findAllByOrderByNameAsc();
}