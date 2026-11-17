package com.masspos.rawmaterial;

import com.masspos.auth.AuthContext;
import com.masspos.catalog.UnitOfMeasure;
import com.masspos.client.CurrentCounter;
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

    /** Bought in, which is the only movement anybody is asked a price or a vendor for. */
    @Transactional
    public RawMaterialView purchase(UUID id, long quantityMilli, String note, long buyingPricePaise,
                                    String vendorName, UUID vendorId) {
        return after(record(id, RawMaterialMovement.PURCHASE, Math.abs(quantityMilli), note,
                buyingPricePaise, vendorName, vendorId), id);
    }

    /** Taken into the kitchen. */
    @Transactional
    public RawMaterialView consume(UUID id, long quantityMilli, String note) {
        return takeOut(id, RawMaterialMovement.CONSUMED, quantityMilli, note);
    }

    /** Spoiled, spilt or thrown out. */
    @Transactional
    public RawMaterialView waste(UUID id, long quantityMilli, String note) {
        return takeOut(id, RawMaterialMovement.WASTAGE, quantityMilli, note);
    }

    /** Sent back to whoever it came from. */
    @Transactional
    public RawMaterialView returnToVendor(UUID id, long quantityMilli, String note) {
        return takeOut(id, RawMaterialMovement.RETURNED_TO_VENDOR, quantityMilli, note);
    }

    /** What a count of the store actually found, either direction. */
    @Transactional
    public RawMaterialView adjust(UUID id, long deltaMilli, String note) {
        if (deltaMilli == 0) {
            throw new FieldRejectedException("deltaMilli", "cannot be nothing: say how far out the count was");
        }
        long inStore = Math.max(0, ledger.inStore(id));
        // A correction that would leave the store owing stock is capped at empty, the same as
        // everything else here. The shop is telling the till what is on the floor, and what is on
        // the floor is never less than nothing.
        long delta = deltaMilli < 0 ? -Math.min(inStore, -deltaMilli) : deltaMilli;
        if (delta == 0) {
            return after(null, id);
        }
        return after(record(id, RawMaterialMovement.ADJUSTMENT, delta, note, 0, "", null), id);
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

    @Transactional(readOnly = true)
    public List<String> vendorsSoFar() {
        return ledger.vendorsSoFar();
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
        return after(record(id, type, -take, said, 0, "", null), id);
    }

    private RawMaterialLedgerEvent record(UUID id, RawMaterialMovement type, long deltaMilli, String note,
                                          long buyingPricePaise, String vendorName, UUID vendorId) {
        return ledger.save(new RawMaterialLedgerEvent(onTheList(id), type, deltaMilli, counter.code(),
                Instant.now(), currentUser(), note, buyingPricePaise, vendorName, vendorId));
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
