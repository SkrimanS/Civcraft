package com.avrgaming.civcraft.modern.campnpc.item;

import java.util.List;
import org.bukkit.configuration.ConfigurationSection;

public record CivCraftItemDefinition(
        String id,
        CivCraftItemCategory category,
        ConfigurationSection section,
        int maxUses,
        double deathDamagePercent
) {
    public String material() {
        return section.getString("material", "STONE");
    }

    public String itemsAdderId() {
        return section.getString("itemsadder", "");
    }

    public String mmoType() {
        return section.getString("mmoitems-type", section.getString("mmoitems.type", section.getString("mmo-type", "")));
    }

    public String mmoId() {
        return section.getString("mmoitems-id", section.getString("mmoitems.id", section.getString("mmo-id", "")));
    }

    public String name() {
        return section.getString("name", id);
    }

    public List<String> lore() {
        return section.getStringList("lore");
    }

    public int amount() {
        return Math.max(1, section.getInt("amount", 1));
    }
}
