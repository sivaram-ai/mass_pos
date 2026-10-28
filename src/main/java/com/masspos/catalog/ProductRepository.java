package com.masspos.catalog;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    /**
     * A deleted item is invisible here, which is what frees its code: the clash check stops seeing
     * it, so a new item can take the code, and a sheet loaded back adds one rather than reviving it.
     * A deleted row's own sku is a tombstone anyway, so it could never match a typed code.
     */
    @Query("select p from Product p where lower(p.sku) = lower(:sku) and p.deleted = false")
    Optional<Product> findBySkuIgnoreCase(@Param("sku") String sku);

    @Query("select p from Product p where p.barcode = :barcode and p.deleted = false")
    Optional<Product> findByBarcode(@Param("barcode") String barcode);

    /**
     * What the billing screen calls on every keystroke. A scanned barcode wins outright; otherwise
     * any part of the item code, the name or the HSN matches, because a cashier types the few
     * characters they remember ("pg" for PG-100), not the whole code. Closest match comes first.
     */
    @Query("""
            select p from Product p
            where p.active = true and p.deleted = false and (
                  p.barcode = :text
               or lower(p.sku) like lower(concat('%', :text, '%'))
               or lower(p.name) like lower(concat('%', :text, '%'))
               or p.hsnCode like concat(:text, '%')
               or p.barcode like concat(:text, '%'))
            order by case when p.barcode = :text then 0
                          when lower(p.sku) = lower(:text) then 1
                          when lower(p.sku) like lower(concat(:text, '%')) then 2
                          when lower(p.name) like lower(concat(:text, '%')) then 3
                          else 4 end,
                     p.name asc
            """)
    List<Product> search(@Param("text") String text, Limit limit);

    List<Product> findByActiveTrueOrderByNameAsc(Limit limit);

    /** The whole catalogue, for the sheet the shop edits in Excel. */
    List<Product> findAllByActiveTrueOrderByNameAsc();

    /**
     * What the Products screen lists: everything the shop still has, stopped items included, in
     * code order and with no cap. The screen sorts and filters this itself, so sorting by price or
     * stock covers the whole catalogue rather than whichever twenty-five the server sent.
     */
    List<Product> findByDeletedFalseOrderBySkuAsc();
}
