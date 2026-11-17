package com.masspos.vendor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface VendorPaymentRepository extends JpaRepository<VendorPayment, UUID> {

    @Query("""
            select p from VendorPayment p
            where p.vendor.id = :vendorId
            order by p.paidOn desc, p.recordedAt desc
            """)
    List<VendorPayment> paidTo(@Param("vendorId") UUID vendorId);

    /** Everything still waiting on an administrator, oldest first: the queue to work through. */
    @Query("""
            select p from VendorPayment p
            where p.approved = false
            order by p.recordedAt asc
            """)
    List<VendorPayment> waitingForApproval();

    /**
     * What has actually been paid, per vendor. Approved only: a payment somebody has recorded but
     * nobody has agreed to must not quietly reduce what the shop believes it owes.
     */
    @Query("""
            select p.vendor.id, coalesce(sum(p.amountPaise), 0)
            from VendorPayment p
            where p.approved = true
            group by p.vendor.id
            """)
    List<Object[]> approvedPerVendor();

    @Query("""
            select p.vendor.id, coalesce(sum(p.amountPaise), 0)
            from VendorPayment p
            where p.approved = false
            group by p.vendor.id
            """)
    List<Object[]> waitingPerVendor();

    /**
     * What has been paid against each order, so an order can say whether it is settled. Approved
     * only, for the same reason the vendor's balance counts only approved money.
     */
    @Query("""
            select p.purchaseOrderId, coalesce(sum(p.amountPaise), 0)
            from VendorPayment p
            where p.approved = true and p.purchaseOrderId is not null
            group by p.purchaseOrderId
            """)
    List<Object[]> approvedPerOrder();

    @Query("""
            select coalesce(sum(p.amountPaise), 0) from VendorPayment p
            where p.approved = true and p.purchaseOrderId = :orderId
            """)
    long approvedForOrder(@Param("orderId") UUID orderId);
}
