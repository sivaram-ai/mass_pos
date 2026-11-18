package com.masspos.purchase;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PurchaseReturnRepository extends JpaRepository<PurchaseReturn, UUID> {

    /** Returns in a window, newest first, optionally one vendor's. */
    @Query("""
            select r from PurchaseReturn r
            where r.returnedOn between :from and :to
              and (:vendorId is null or r.vendor.id = :vendorId)
            order by r.returnedOn desc, r.id desc
            """)
    List<PurchaseReturn> search(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                @Param("vendorId") UUID vendorId);

    /**
     * What has gone back against each order, in milli-units times paise - divided by the caller,
     * as money always is here. An order that half of went back on is not owed in full, and the
     * order's own page has to say the same thing the vendor's balance does.
     */
    @Query("""
            select l.sentBack.againstOrderId, coalesce(sum(l.quantityMilli * l.unitCostPaise), 0)
            from PurchaseReturnLine l
            where l.sentBack.againstOrderId is not null
            group by l.sentBack.againstOrderId
            """)
    List<Object[]> sentBackPerOrder();

    @Query("""
            select coalesce(sum(l.quantityMilli * l.unitCostPaise), 0)
            from PurchaseReturnLine l
            where l.sentBack.againstOrderId = :orderId
            """)
    long sentBackForOrder(@Param("orderId") UUID orderId);

    /** Everything sent back against one order, which is what its page has to show. */
    @Query("select r from PurchaseReturn r where r.againstOrderId = :orderId order by r.returnedOn desc")
    List<PurchaseReturn> against(@Param("orderId") UUID orderId);
}
