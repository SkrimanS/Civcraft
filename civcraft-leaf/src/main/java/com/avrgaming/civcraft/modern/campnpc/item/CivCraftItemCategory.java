package com.avrgaming.civcraft.modern.campnpc.item;

import java.util.Locale;

public enum CivCraftItemCategory {
    ARMOR("armor", true),
    WEAPONS("weapons", true),
    BOWS("bows", true),
    TOOLS("tools", true),
    CONSUMABLES("consumables", false);

    private final String fileName;
    private final boolean deathDurability;

    CivCraftItemCategory(String fileName, boolean deathDurability) {
        this.fileName = fileName;
        this.deathDurability = deathDurability;
    }

    public String fileName() {
        return fileName;
    }

    public boolean deathDurability() {
        return deathDurability;
    }

    public static CivCraftItemCategory from(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (CivCraftItemCategory category : values()) {
            if (category.fileName.equals(clean) || category.name().equalsIgnoreCase(clean)) {
                return category;
            }
        }
        return CONSUMABLES;
    }
}
