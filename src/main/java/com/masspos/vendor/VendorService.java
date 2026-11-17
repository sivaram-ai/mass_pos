package com.masspos.vendor;

import com.masspos.auth.AuthContext;
import com.masspos.common.IndiaTime;
import com.masspos.inventory.InventoryLedgerEvent;
import com.masspos.inventory.InventoryLedgerRepository;
import com.masspos.rawmaterial.RawMaterialLedgerEvent;
import com.masspos.rawmaterial.RawMaterialLedgerRepository;
import com.masspos.user.User;
import com.masspos.user.UserRepository;
import com.masspos.web.FieldRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The people the shop buys from, what it has had from them and what it still owes.
 *
 * <p>The balance is deliberately simple arithmetic over things that already existed: everything
 * bought from a vendor, at the price it was bought for, less every payment an administrator has
 * agreed to. Nothing is accrued, nothing is posted, there are no accounts - a shopkeeper asking
 * "what do I owe the vegetable man" is asking a question with one answer, and it should not need a
 * ledger of its own to produce it.
 */
@Service
public class VendorService {

    private final VendorRepository vendors;
    private final VendorPaymentRepository payments;
    private final InventoryLedgerRepository shelf;
    private final RawMaterialLedgerRepository store;
    private final UserRepository users;

    public VendorService(VendorRepository vendors, VendorPaymentRepository payments,
                         InventoryLedgerRepository shelf, RawMaterialLedgerRepository store,
                         UserRepository users) {
        this.vendors = vendors;
        this.payments = payments;
        this.shelf = shelf;
        this.store = store;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<VendorView> list() {
        Map<UUID, Long> boughtById = new HashMap<>();
        Map<String, Long> boughtByName = new HashMap<>();
        addUp(shelf.boughtPerVendorId(), boughtById);
        addUp(store.boughtPerVendorId(), boughtById);
        addUpByName(shelf.boughtPerVendorName(), boughtByName);
        addUpByName(store.boughtPerVendorName(), boughtByName);
        Map<UUID, Long> paid = byId(payments.approvedPerVendor());
        Map<UUID, Long> waiting = byId(payments.waitingPerVendor());

        return vendors.onTheList().stream()
                .map(vendor -> VendorView.of(vendor,
                        money(boughtById.getOrDefault(vendor.getId(), 0L)
                                + boughtByName.getOrDefault(vendor.getName(), 0L)),
                        paid.getOrDefault(vendor.getId(), 0L),
                        waiting.getOrDefault(vendor.getId(), 0L)))
                .toList();
    }

    /** Everything about one of them, for the page somebody opens to settle up. */
    @Transactional(readOnly = true)
    public VendorProfile profile(UUID id) {
        Vendor vendor = onTheList(id);
        List<VendorProfile.Purchase> bought = new ArrayList<>();
        for (InventoryLedgerEvent event : shelf.boughtFrom(id, vendor.getName())) {
            bought.add(new VendorProfile.Purchase(event.getId(), event.getOccurredAt(), false,
                    event.getProduct().getSku(), event.getProduct().getName(), event.getProduct().getUnit(),
                    event.getQuantityDeltaMilli(), event.getBuyingPricePaise(),
                    money(event.getQuantityDeltaMilli() * event.getBuyingPricePaise()),
                    event.getNote() == null ? "" : event.getNote(), who(event.getRecordedBy())));
        }
        for (RawMaterialLedgerEvent event : store.boughtFrom(id, vendor.getName())) {
            bought.add(new VendorProfile.Purchase(event.getId(), event.getOccurredAt(), true,
                    event.getRawMaterial().getCode(), event.getRawMaterial().getName(),
                    event.getRawMaterial().getUnit(), event.getQuantityDeltaMilli(), event.getBuyingPricePaise(),
                    money(event.getQuantityDeltaMilli() * event.getBuyingPricePaise()),
                    event.getNote(), who(event.getRecordedBy())));
        }
        // One vendor, one page: the shelf and the store are the shop's business, not his.
        bought.sort(Comparator.comparing(VendorProfile.Purchase::occurredAt).reversed());

        List<VendorProfile.Payment> paid = payments.paidTo(id).stream()
                .map(payment -> new VendorProfile.Payment(payment.getId(), payment.getPaidOn(),
                        payment.getAmountPaise(), payment.getMode(), payment.getReference(), payment.getNote(),
                        who(payment.getRecordedBy()), payment.isApproved(), who(payment.getApprovedBy()),
                        payment.getApprovedAt(), payment.getPurchaseOrderId()))
                .toList();

        long boughtPaise = bought.stream().mapToLong(VendorProfile.Purchase::valuePaise).sum();
        long approved = paid.stream().filter(VendorProfile.Payment::approved)
                .mapToLong(VendorProfile.Payment::amountPaise).sum();
        long waiting = paid.stream().filter(payment -> !payment.approved())
                .mapToLong(VendorProfile.Payment::amountPaise).sum();
        return new VendorProfile(VendorView.of(vendor, boughtPaise, approved, waiting), bought, paid);
    }

    @Transactional
    public VendorView create(String code, String name, String phone, String gstin, String address, String note) {
        refuseATakenCode(code, null);
        return VendorView.of(vendors.save(new Vendor(code, name, phone, gstin, address, note)), 0, 0, 0);
    }

    @Transactional
    public VendorView update(UUID id, String code, String name, String phone, String gstin, String address,
                             String note, boolean active) {
        Vendor vendor = onTheList(id);
        refuseATakenCode(code, id);
        vendor.describe(code, name, phone, gstin, address, note);
        if (active) {
            vendor.start();
        } else {
            vendor.stop();
        }
        return profile(id).vendor();
    }

    @Transactional
    public void delete(UUID id) {
        onTheList(id).delete();
    }

    /**
     * Records money handed over. It waits for an administrator before it counts against what the
     * shop owes - the person who paid is rarely the person who decides what gets paid.
     */
    @Transactional
    public VendorProfile.Payment pay(UUID vendorId, long amountPaise, LocalDate paidOn, VendorPaymentMode mode,
                                     String reference, String note, UUID purchaseOrderId) {
        if (amountPaise <= 0) {
            throw new FieldRejectedException("amountPaise", "must be more than nothing");
        }
        Vendor vendor = onTheList(vendorId);
        VendorPayment payment = payments.save(new VendorPayment(vendor, amountPaise,
                paidOn == null ? LocalDate.now(IndiaTime.ZONE) : paidOn, mode, reference, note,
                currentUser(), Instant.now(), purchaseOrderId));
        return new VendorProfile.Payment(payment.getId(), payment.getPaidOn(), payment.getAmountPaise(),
                payment.getMode(), payment.getReference(), payment.getNote(), who(payment.getRecordedBy()),
                false, "", null, payment.getPurchaseOrderId());
    }

    /** Only an administrator, and only once: agreeing twice to the same money is not a thing. */
    @Transactional
    public void approve(UUID paymentId) {
        VendorPayment payment = payments.findById(paymentId).orElseThrow(() -> new IllegalArgumentException(
                "That payment is not on this till any more. Refresh the screen and try again."));
        if (payment.isApproved()) {
            throw new IllegalStateException("That payment has already been approved.");
        }
        payment.approve(currentUser(), Instant.now());
    }

    /** What an administrator has waiting, oldest first. */
    @Transactional(readOnly = true)
    public List<VendorProfile.Payment> waitingForApproval() {
        return payments.waitingForApproval().stream()
                .map(payment -> new VendorProfile.Payment(payment.getId(), payment.getPaidOn(),
                        payment.getAmountPaise(), payment.getMode(), payment.getReference(),
                        payment.getVendor().getName(), who(payment.getRecordedBy()), false, "", null,
                        payment.getPurchaseOrderId()))
                .toList();
    }

    private void refuseATakenCode(String code, UUID exceptId) {
        vendors.findByCode(code == null ? "" : code.trim())
                .filter(other -> !other.getId().equals(exceptId))
                .ifPresent(other -> {
                    throw new FieldRejectedException("code", "is already used by " + other.getName());
                });
    }

    private Vendor onTheList(UUID id) {
        return vendors.onTheList(id).orElseThrow(() -> new IllegalArgumentException(
                "That vendor is not on this till any more. Refresh the screen and try again."));
    }

    private User currentUser() {
        return users.findById(AuthContext.require().userId())
                .orElseThrow(() -> new IllegalStateException("Signed-in user has disappeared"));
    }

    private static String who(User user) {
        return user == null ? "" : user.getDisplayName();
    }

    /** Milli-units times paise is a thousand times the money; rounded once, at the end. */
    private static long money(long milliTimesPaise) {
        return Math.round(milliTimesPaise / 1000.0);
    }

    private static void addUp(List<Object[]> rows, Map<UUID, Long> into) {
        for (Object[] row : rows) {
            into.merge((UUID) row[0], ((Number) row[1]).longValue(), Long::sum);
        }
    }

    /** Deliveries taken before the shop kept a list, which carry a name and no link. */
    private static void addUpByName(List<Object[]> rows, Map<String, Long> into) {
        for (Object[] row : rows) {
            into.merge((String) row[0], ((Number) row[1]).longValue(), Long::sum);
        }
    }

    private static Map<UUID, Long> byId(List<Object[]> rows) {
        Map<UUID, Long> totals = new HashMap<>();
        addUp(rows, totals);
        return totals;
    }
}
