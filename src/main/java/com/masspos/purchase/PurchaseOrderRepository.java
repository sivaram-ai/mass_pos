package com.masspos.purchase;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    /**
     * Orders in a window, newest first, optionally one vendor's or one state's.
     *
     * <p>One query with nulls meaning "any", rather than four method names: this is the screen's
     * only question, asked with whichever boxes somebody has filled in.
     */
    @Query("""
            select o from PurchaseOrder o
            where o.orderedOn between :from and :to
              and (:vendorId is null or o.vendor.id = :vendorId)
              and (:status is null or o.status = :status)
            order by o.orderedOn desc, o.id desc
            """)
    List<PurchaseOrder> search(@Param("from") LocalDate from, @Param("to") LocalDate to,
                               @Param("vendorId") UUID vendorId, @Param("status") PurchaseOrderStatus status);

    @Query("select o from PurchaseOrder o where o.number = :number")
    Optional<PurchaseOrder> findByNumber(@Param("number") String number);

    /** What is waiting on an administrator, oldest first: the queue to work through. */
    @Query("""
            select o from PurchaseOrder o
            where o.status = com.masspos.purchase.PurchaseOrderStatus.PENDING_APPROVAL
            order by o.orderedOn asc, o.id asc
            """)
    List<PurchaseOrder> waitingForApproval();
}
