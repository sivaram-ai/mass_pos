package com.masspos.rawmaterial;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RawMaterialRepository extends JpaRepository<RawMaterial, UUID> {

    /**
     * Everything the shop still has on its list, stopped ones included: the goods are in the store
     * whether or not anybody is cooking with them, and whoever counts them needs to see them.
     * Deleted ones are excluded in the query rather than by method name, so a freed code really is
     * free - exactly as the catalogue does it.
     */
    @Query("select m from RawMaterial m where m.deleted = false order by m.code")
    List<RawMaterial> onTheList();

    @Query("select m from RawMaterial m where m.deleted = false and upper(m.code) = upper(:code)")
    Optional<RawMaterial> findByCode(@Param("code") String code);

    @Query("select m from RawMaterial m where m.deleted = false and m.id = :id")
    Optional<RawMaterial> onTheList(@Param("id") UUID id);
}
