package com.avrgaming.civcraft.modern.campnpc.gui;

import com.avrgaming.civcraft.modern.campnpc.util.ItemsAdderHook;
import com.avrgaming.civcraft.modern.campnpc.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class ItemBuilder {
    private final ItemsAdderHook itemsAdder = new ItemsAdderHook();

    public ItemStack build(ConfigurationSection section, Map<String, String> placeholders) {
        String itemsAdderId = section.getString("itemsadder", null);
        ItemStack item = null;
        if (itemsAdderId != null && !itemsAdderId.isBlank()) {
            item = itemsAdder.create(itemsAdderId).orElse(null);
        }
        if (item == null) {
            Material material = Material.matchMaterial(section.getString("material", "STONE").toUpperCase());
            item = new ItemStack(material == null ? Material.STONE : material);
        }
        item.setAmount(Math.max(1, section.getInt("amount", 1)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (section.contains("name")) {
                meta.displayName(Text.component(Text.apply(section.getString("name", ""), placeholders)));
            }
            if (section.isList("lore")) {
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                for (String line : section.getStringList("lore")) {
                    lore.add(Text.component(Text.apply(line, placeholders)));
                }
                meta.lore(lore);
            }
            if (section.contains("custom-model-data")) {
                meta.setCustomModelData(section.getInt("custom-model-data"));
            }
            if (section.getBoolean("unbreakable", false)) {
                meta.setUnbreakable(true);
                meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
            }
            if (section.getBoolean("glow", false)) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            for (String flag : section.getStringList("flags")) {
                try {
                    meta.addItemFlags(ItemFlag.valueOf(flag.trim().toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                }
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
