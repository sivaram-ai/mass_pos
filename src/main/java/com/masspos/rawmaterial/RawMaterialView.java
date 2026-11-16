package com.masspos.rawmaterial;

import com.masspos.catalog.UnitOfMeasure;

import java.util.UUID;

/**
 * A raw material as the screen lists it, with what the store holds.
 *
 * @param lowOnIt true when the store is at or below the level the shop asked to be told about,
 *                and somebody should put it on the next order
 */
public record RawMaterialView(UUID id, String code, String name, UnitOfMeasure unit, long inStoreMilli,
                              long reorderLevelMilli, boolean active, boolean lowOnIt) {

    static RawMaterialView of(RawMaterial material, long inStoreMilli) {
        return new RawMaterialView(material.getId(), material.getCode(), material.getName(), material.getUnit(),
                inStoreMilli, material.getReorderLevelMilli(), material.isActive(),
                material.getReorderLevelMilli() > 0 && inStoreMilli <= material.getReorderLevelMilli());
    }
}
