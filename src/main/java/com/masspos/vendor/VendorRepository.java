package com.masspos.vendor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VendorRepository extends JpaRepository<Vendor, UUID> {

    /** Everyone still on the list, stopped ones included: the shop may still owe them money. */
    @Query("select v from Vendor v where v.deleted = false order by v.name")
    List<Vendor> onTheList();

    @Query("select v from Vendor v where v.deleted = false and v.id = :id")
    Optional<Vendor> onTheList(@Param("id") UUID id);

    @Query("select v from Vendor v where v.deleted = false and upper(v.code) = upper(:code)")
    Optional<Vendor> findByCode(@Param("code") String code);
}
