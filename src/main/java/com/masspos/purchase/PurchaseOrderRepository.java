package com.masspos.purchase;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
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

    /** The order written to replace this one, where there is one: the next version of it. */
    @Query("select o from PurchaseOrder o where o.replacesOrderNumber = :number")
    Optional<PurchaseOrder> replacing(@Param("number") String number);

    /** The orders a list of deliveries names, in one query rather than one apiece. */
    @Query("select o from PurchaseOrder o where o.number in :numbers")
    List<PurchaseOrder> byNumbers(@Param("numbers") Collection<String> numbers);

    /**
     * What the one-off lines of received orders have come to, per vendor. Nothing counts them, so
     * nothing else can tell a vendor's balance about them. Summed in milli-units times paise and
     * divided by the caller, as money is everywhere here.
     */
    @Query("""
            select l.order.vendor.id, coalesce(sum(l.receivedMilli * l.unitCostPaise), 0)
            from PurchaseOrderLine l
            where l.order.status = com.masspos.purchase.PurchaseOrderStatus.RECEIVED
              and l.product is null and l.rawMaterial is null and l.order.vendor is not null
            group by l.order.vendor.id
            """)
    List<Object[]> oneOffsPerVendor();

    /** One vendor's received orders, newest first, for the one-off lines on his page. */
    @Query("""
            select o from PurchaseOrder o
            where o.vendor.id = :vendorId
              and o.status = com.masspos.purchase.PurchaseOrderStatus.RECEIVED
            order by o.orderedOn desc, o.id desc
            """)
    List<PurchaseOrder> receivedFrom(@Param("vendorId") UUID vendorId);

    /** Every order ever written to one vendor, newest first: his history, whatever became of them. */
    @Query("""
            select o from PurchaseOrder o
            where o.vendor.id = :vendorId
            order by o.orderedOn desc, o.id desc
            """)
    List<PurchaseOrder> everOrderedFrom(@Param("vendorId") UUID vendorId);

    /** What is waiting on an administrator, oldest first: the queue to work through. */
    @Query("""
            select o from PurchaseOrder o
            where o.status = com.masspos.purchase.PurchaseOrderStatus.PENDING_APPROVAL
            order by o.orderedOn asc, o.id asc
            """)
    List<PurchaseOrder> waitingForApproval();
}
