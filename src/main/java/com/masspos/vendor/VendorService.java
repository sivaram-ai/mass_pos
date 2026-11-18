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
    private final AlsoBought alsoBought;

    public VendorService(VendorRepository vendors, VendorPaymentRepository payments,
                         InventoryLedgerRepository shelf, RawMaterialLedgerRepository store,
                         UserRepository users, AlsoBought alsoBought) {
        this.vendors = vendors;
        this.payments = payments;
        this.shelf = shelf;
        this.store = store;
        this.users = users;
        this.alsoBought = alsoBought;
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
        // And the things no ledger counts, which are money all the same.
        Map<UUID, Long> oneOffs = alsoBought.perVendor();

        return vendors.onTheList().stream()
                .map(vendor -> VendorView.of(vendor,
                        money(boughtById.getOrDefault(vendor.getId(), 0L)
                                + boughtByName.getOrDefault(vendor.getName(), 0L))
                                + oneOffs.getOrDefault(vendor.getId(), 0L),
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
        bought.addAll(alsoBought.of(id));
        // One vendor, one page: the shelf and the store are the shop's business, not his.
        bought.sort(Comparator.comparing(VendorProfile.Purchase::occurredAt).reversed());

        List<VendorProfile.Payment> paid = payments.paidTo(id).stream()
                .map(VendorProfile.Payment::of)
                .toList();

        long boughtPaise = bought.stream().mapToLong(VendorProfile.Purchase::valuePaise).sum();
        long approved = paid.stream().filter(VendorProfile.Payment::approved)
                .mapToLong(VendorProfile.Payment::amountPaise).sum();
        long waiting = paid.stream().filter(payment -> !payment.approved())
                .mapToLong(VendorProfile.Payment::amountPaise).sum();
        return new VendorProfile(VendorView.of(vendor, boughtPaise, approved, waiting), bought, paid);
    }

    /**
     * Everything that has happened with one vendor, newest first.
     *
     * <p>The profile answers what the shop owes him; this answers why, which is the question
     * anybody asks the moment the figure looks wrong. Assembled from the records themselves - the
     * orders, both ledgers and the payments - so it can never drift from them, and nothing here is
     * stored as history.
     *
     * <p>A delivery and a return are the same ledger row with opposite signs, so the sign is what
     * decides which it was. Nothing else can: a return carries a negative quantity precisely so
     * that quantity times price takes money off the balance without anybody working anything out.
     */
    @Transactional(readOnly = true)
    public List<VendorHistory.Event> history(UUID id) {
        Vendor vendor = onTheList(id);
        List<VendorHistory.Event> events = new ArrayList<>(alsoBought.ordersOf(id));

        for (InventoryLedgerEvent event : shelf.boughtFrom(id, vendor.getName())) {
            events.add(movement(event.getId().toString(), event.getOccurredAt(),
                    event.getQuantityDeltaMilli(), event.getBuyingPricePaise(),
                    event.getProduct().getSku() + " " + event.getProduct().getName(),
                    event.getProduct().getUnit().name(), event.getOrderNumber(),
                    event.getNote() == null ? "" : event.getNote(), who(event.getRecordedBy())));
        }
        for (RawMaterialLedgerEvent event : store.boughtFrom(id, vendor.getName())) {
            events.add(movement(event.getId().toString(), event.getOccurredAt(),
                    event.getQuantityDeltaMilli(), event.getBuyingPricePaise(),
                    event.getRawMaterial().getCode() + " " + event.getRawMaterial().getName(),
                    event.getRawMaterial().getUnit().name(), event.getOrderNumber(),
                    event.getNote(), who(event.getRecordedBy())));
        }
        // The things no ledger counts, which the shop bought and paid for all the same.
        for (VendorProfile.Purchase oneOff : alsoBought.of(id)) {
            events.add(new VendorHistory.Event(oneOff.id().toString(), oneOff.occurredAt(),
                    VendorHistory.Kind.BOUGHT, oneOff.note(), oneOff.name(), oneOff.quantityMilli(),
                    oneOff.unit().name(), oneOff.valuePaise(), oneOff.by(), "Not counted"));
        }
        for (VendorPayment payment : payments.paidTo(id)) {
            events.add(new VendorHistory.Event(payment.getId().toString(), payment.getRecordedAt(),
                    VendorHistory.Kind.PAID, reference(payment), payment.getNote(), 0, "",
                    payment.getAmountPaise(), who(payment.getRecordedBy()),
                    payment.isApproved() ? "Approved" : "Waiting"));
        }

        events.sort(Comparator.comparing(VendorHistory.Event::when).reversed());
        return events;
    }

    /** A delivery, or the same thing in reverse: the sign on the quantity is what tells them apart. */
    private static VendorHistory.Event movement(String id, Instant when, long quantityMilli,
                                                long pricePaise, String what, String unit,
                                                String orderNumber, String note, String by) {
        boolean back = quantityMilli < 0;
        long value = Math.round(Math.abs(quantityMilli) * pricePaise / 1000.0);
        return new VendorHistory.Event(id, when,
                back ? VendorHistory.Kind.SENT_BACK : VendorHistory.Kind.BOUGHT,
                orderNumber == null || orderNumber.isEmpty() ? note : orderNumber,
                what, Math.abs(quantityMilli), unit, value, by, back ? "Went back" : "");
    }

    /** What a payment can be found by again: the way it was paid, and the reference if there is one. */
    private static String reference(VendorPayment payment) {
        String how = payment.getMode().name();
        return payment.getReference().isEmpty() ? how : how + " " + payment.getReference();
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
     *
     * <p>The vendor is optional, as it is on an order. A shop that pays the man off the lorry has
     * paid him, and the one thing a till must not do is lose that: the payment is written with his
     * name on it and nothing else. There is no running balance for it to move - he has no page -
     * so where it shows is the order it was paid for.
     *
     * @param vendorName who was paid, used only where there is no vendor row to take the name from
     */
    @Transactional
    public VendorProfile.Payment pay(UUID vendorId, String vendorName, long amountPaise, LocalDate paidOn,
                                     VendorPaymentMode mode, String reference, String note,
                                     UUID purchaseOrderId) {
        if (amountPaise <= 0) {
            throw new FieldRejectedException("amountPaise", "must be more than nothing");
        }
        Vendor vendor = vendorId == null ? null : onTheList(vendorId);
        VendorPayment payment = payments.save(new VendorPayment(vendor, vendorName, amountPaise,
                paidOn == null ? LocalDate.now(IndiaTime.ZONE) : paidOn, mode, reference, note,
                currentUser(), Instant.now(), purchaseOrderId));
        return VendorProfile.Payment.of(payment);
    }

    /**
     * Everything paid against one order, agreed to or not, oldest first.
     *
     * <p>The order's own page shows these, which is the only place a payment to somebody off the
     * list can be seen - and therefore the only place an administrator can agree to one.
     */
    @Transactional(readOnly = true)
    public List<VendorProfile.Payment> againstOrder(UUID purchaseOrderId) {
        return payments.allocatedTo(purchaseOrderId).stream().map(VendorProfile.Payment::of).toList();
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
        return payments.waitingForApproval().stream().map(VendorProfile.Payment::of).toList();
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
