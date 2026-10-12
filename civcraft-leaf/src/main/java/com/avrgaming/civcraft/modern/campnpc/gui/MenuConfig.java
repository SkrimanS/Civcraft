package com.avrgaming.civcraft.modern.campnpc.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

public final class MenuConfig {
    public final String id;
    public final String title;
    public final int size;
    public final List<MenuItem> items;

    public MenuConfig(String id, String title, int size, List<MenuItem> items) {
        this.id = id;
        this.title = title;
        this.size = Math.max(9, Math.min(54, ((size + 8) / 9) * 9));
        this.items = items;
    }

    public static MenuConfig from(String id, ConfigurationSection section, ItemBuilder itemBuilder, Map<String, String> placeholders) {
        String title = section.getString("title", id);
        int size = section.getInt("size", 27);
        List<MenuItem> items = new ArrayList<>();
        ConfigurationSection itemsSection = section.getConfigurationSection("items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                ConfigurationSection itemSection = itemsSection.getConfigurationSection(key);
                if (itemSection == null) {
                    continue;
                }
                List<Integer> slots = new ArrayList<>();
                if (itemSection.isList("slots")) {
                    slots.addAll(itemSection.getIntegerList("slots"));
                } else {
                    slots.add(itemSection.getInt("slot", 0));
                }
                List<GuiAction> actions = new ArrayList<>();
                for (String raw : itemSection.getStringList("actions")) {
                    actions.add(GuiAction.parse(raw));
                }
                ItemStack stack = itemBuilder.build(itemSection, placeholders);
                items.add(new MenuItem(slots, stack, actions));
            }
        }
        return new MenuConfig(id, title, size, items);
    }
}
