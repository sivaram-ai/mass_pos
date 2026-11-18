package com.masspos.rawmaterial;

import com.masspos.auth.AuthContext;
import com.masspos.catalog.UnitOfMeasure;
import com.masspos.client.CurrentCounter;
import com.masspos.inventory.CorrectionReason;
import com.masspos.user.User;
import com.masspos.user.UserRepository;
import com.masspos.web.FieldRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The store behind the kitchen: what the shop buys, uses and throws away but never sells.
 *
 * <p>Worked exactly like stock on the shelf, because it is the same question - what is there, and
 * how did it get that way - and a shopkeeper should not have to learn it twice. What is in the
 * store is the sum of movements; a correction is a new movement, never an edit; and the count
 * stops at empty.
 */
@Service
public class RawMaterialService {

    private final RawMaterialRepository materials;
    private final RawMaterialLedgerRepository ledger;
    private final UserRepository users;
    private final CurrentCounter counter;

    public RawMaterialService(RawMaterialRepository materials, RawMaterialLedgerRepository ledger,
                              UserRepository users, CurrentCounter counter) {
        this.materials = materials;
        this.ledger = ledger;
        this.users = users;
        this.counter = counter;
    }

    @Transactional(readOnly = true)
    public List<RawMaterialView> list() {
        Map<UUID, Long> inStore = inStoreForAll();
        return materials.onTheList().stream()
                .map(material -> RawMaterialView.of(material, inStore.getOrDefault(material.getId(), 0L)))
                .toList();
    }

    @Transactional
    public RawMaterialView create(String code, String name, UnitOfMeasure unit, long reorderLevelMilli) {
        refuseATakenCode(code, null);
        RawMaterial material = materials.save(new RawMaterial(code, name, unit, reorderLevelMilli));
        return RawMaterialView.of(material, 0);
    }

    @Transactional
    public RawMaterialView update(UUID id, String code, String name, UnitOfMeasure unit, long reorderLevelMilli,
                                  boolean active) {
        RawMaterial material = onTheList(id);
        refuseATakenCode(code, id);
        material.describe(code, name, unit, reorderLevelMilli);
        if (active) {
            material.start();
        } else {
            material.stop();
        }
        return RawMaterialView.of(material, ledger.inStore(id));
    }

    /**
     * Gone from the list, with its code free again. The movements stay: they are append-only, and
     * they are what every count was made of.
     */
    @Transactional
    public void delete(UUID id) {
        onTheList(id).delete();
    }

    /**
     * Bought in, against an order somebody agreed to - the only way anything reaches the store
     * now, and the only movement anybody is asked a price or a vendor for.
     *
     * <p>Called by the purchase-order service when the goods actually turn up. There is no screen
     * behind it any more: a manager typing a delivery straight in was how the store filled up
     * with purchases nobody had approved.
     */
    @Transactional
    public RawMaterialView purchase(UUID id, long quantityMilli, String note, long buyingPricePaise,
                                    String vendorName, UUID vendorId, String orderNumber) {
        return after(record(id, RawMaterialMovement.PURCHASE, Math.abs(quantityMilli), note,
                buyingPricePaise, vendorName, vendorId, orderNumber), id);
    }

    /**
     * Going back to the vendor, with what it cost and the return it went back on - the store's
     * half of the same movement the shelf records, and carrying the same figures so that what the
     * shop owes comes down by what went back.
     *
     * @return what actually went back, which is less than asked for when the store has run down
     */
    @Transactional
    public long sendBack(UUID id, long quantityMilli, String note, long buyingPricePaise,
                         String vendorName, UUID vendorId, String returnNumber) {
        long inStore = Math.max(0, ledger.inStore(id));
        long going = Math.min(inStore, Math.abs(quantityMilli));
        if (going <= 0) {
            onTheList(id);
            return 0;
        }
        record(id, RawMaterialMovement.RETURNED_TO_VENDOR, -going, note, buyingPricePaise, vendorName,
                vendorId, returnNumber);
        return going;
    }

    /** Taken into the kitchen. */
    @Transactional
    public RawMaterialView consume(UUID id, long quantityMilli, String note) {
        return takeOut(id, RawMaterialMovement.CONSUMED, quantityMilli, note);
    }

    /**
     * A count put right by hand: how much it moves, why, and whatever was typed beside it.
     *
     * <p>The reason chooses the movement, so what used to be three buttons - Wasted, Sent back,
     * Correct - is one dialog asking the question somebody at a counter would actually be asked:
     * why is this figure wrong. Capped at empty, as everything else here is.
     */
    @Transactional
    public RawMaterialView correct(UUID id, long deltaMilli, CorrectionReason reason, String notes) {
        long wanted = reason.applyTo(deltaMilli);
        if (wanted == 0) {
            throw new FieldRejectedException("deltaMilli", "cannot be nothing: say how far out the count was");
        }
        long inStore = Math.max(0, ledger.inStore(id));
        long delta = wanted < 0 ? -Math.min(inStore, -wanted) : wanted;
        if (delta == 0) {
            return after(null, id);
        }
        return after(record(id, inTheStore(reason), delta, reason.wording(notes), 0, "", null, ""), id);
    }

    /**
     * Which of the store's own movements a reason writes. The store has its own enum and its own
     * table - adding the shelf's values to it, or the other way round, would mean altering a CHECK
     * constraint on a till already in a shop, which SQLite will not do.
     */
    private static RawMaterialMovement inTheStore(CorrectionReason reason) {
        return switch (reason) {
            case OPENING_STOCK -> RawMaterialMovement.OPENING_STOCK;
            case DAMAGED, EXPIRED, WASTED, TAKEN -> RawMaterialMovement.WASTAGE;
            case SENT_BACK -> RawMaterialMovement.RETURNED_TO_VENDOR;
            case STOCK_TAKE, ENTERED_WRONGLY -> RawMaterialMovement.ADJUSTMENT;
        };
    }

    /**
     * Everything that has ever moved one material, newest first, each row carrying what the store
     * held once it had been applied. Walked backwards from today's count, as the shelf's is.
     */
    @Transactional(readOnly = true)
    public List<RawMaterialMovementView> historyOf(UUID materialId) {
        List<RawMaterialLedgerEvent> events = ledger.historyOf(materialId);
        long running = events.stream().mapToLong(RawMaterialLedgerEvent::getQuantityDeltaMilli).sum();
        List<RawMaterialMovementView> history = new ArrayList<>(events.size());
        for (RawMaterialLedgerEvent event : events) {
            history.add(RawMaterialMovementView.of(event, running));
            running -= event.getQuantityDeltaMilli();
        }
        return history;
    }

    /** Every material's movements over a window, with no running count: see the shelf's version. */
    @Transactional(readOnly = true)
    public List<RawMaterialMovementView> historySince(Instant from) {
        return ledger.between(from, Instant.now()).stream()
                .map(event -> RawMaterialMovementView.of(event, null))
                .toList();
    }


    /**
     * Takes something out of the store, and never past empty - the same floor the shelf has. What
     * was actually taken off comes back on the view, so a manager who typed five and had three can
     * see it and put the count right.
     */
    private RawMaterialView takeOut(UUID id, RawMaterialMovement type, long quantityMilli, String note) {
        long wanted = Math.abs(quantityMilli);
        long inStore = Math.max(0, ledger.inStore(id));
        long take = Math.min(inStore, wanted);
        if (take <= 0) {
            onTheList(id);
            return after(null, id);
        }
        String said = take < wanted ? trim(note) + " - the store ran out, so only part of it came off" : note;
        return after(record(id, type, -take, said, 0, "", null, ""), id);
    }

    private RawMaterialLedgerEvent record(UUID id, RawMaterialMovement type, long deltaMilli, String note,
                                          long buyingPricePaise, String vendorName, UUID vendorId,
                                          String orderNumber) {
        return ledger.save(new RawMaterialLedgerEvent(onTheList(id), type, deltaMilli, counter.code(),
                Instant.now(), currentUser(), note, buyingPricePaise, vendorName, vendorId, orderNumber));
    }

    private RawMaterialView after(RawMaterialLedgerEvent ignored, UUID id) {
        return RawMaterialView.of(onTheList(id), ledger.inStore(id));
    }

    private void refuseATakenCode(String code, UUID exceptId) {
        materials.findByCode(code == null ? "" : code.trim())
                .filter(other -> !other.getId().equals(exceptId))
                .ifPresent(other -> {
                    throw new FieldRejectedException("code", "is already used by " + other.getName());
                });
    }

    private RawMaterial onTheList(UUID id) {
        return materials.onTheList(id).orElseThrow(() -> new IllegalArgumentException(
                "That raw material is not on this till any more. Refresh the screen and try again."));
    }

    private User currentUser() {
        return users.findById(AuthContext.require().userId())
                .orElseThrow(() -> new IllegalStateException("Signed-in user has disappeared"));
    }

    private Map<UUID, Long> inStoreForAll() {
        Map<UUID, Long> inStore = new HashMap<>();
        for (Object[] row : ledger.inStoreForAll()) {
            inStore.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return inStore;
    }

    private static String trim(String note) {
        return note == null ? "" : note.trim();
    }
}
