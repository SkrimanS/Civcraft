package com.avrgaming.civcraft.modern.campnpc.quest;

import com.avrgaming.civcraft.modern.campnpc.util.ItemsAdderHook;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public record ItemMatcher(Material material, Integer customModelData, String itemsAdderId) {
    public static ItemMatcher from(ConfigurationSection section) {
        if (section == null) {
            return new ItemMatcher(Material.AIR, null, null);
        }
        String materialName = section.getString("material", "AIR").toUpperCase();
        Material material = Material.matchMaterial(materialName);
        Integer customModelData = section.contains("custom-model-data") ? section.getInt("custom-model-data") : null;
        String itemsAdder = section.getString("itemsadder", null);
        return new ItemMatcher(material == null ? Material.AIR : material, customModelData, itemsAdder);
    }

    public boolean matches(ItemStack item, ItemsAdderHook itemsAdder) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (itemsAdderId != null && !itemsAdderId.isBlank()) {
            if (itemsAdder.idOf(item).filter(itemsAdderId::equalsIgnoreCase).isPresent()) {
                return true;
            }
        }
        if (material != Material.AIR && item.getType() != material) {
            return false;
        }
        if (customModelData != null) {
            ItemMeta meta = item.getItemMeta();
            return meta != null && meta.hasCustomModelData() && meta.getCustomModelData() == customModelData;
        }
        return material != Material.AIR;
    }
}
