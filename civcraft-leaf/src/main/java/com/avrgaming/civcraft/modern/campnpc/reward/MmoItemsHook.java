package com.avrgaming.civcraft.modern.campnpc.reward;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

public final class MmoItemsHook {
    public Optional<ItemStack> create(String typeId, String itemId) {
        if (typeId == null || typeId.isBlank() || itemId == null || itemId.isBlank()) {
            return Optional.empty();
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("MMOItems")) {
            return Optional.empty();
        }
        try {
            Class<?> mmoItemsClass = Class.forName("net.Indyuce.mmoitems.MMOItems");
            Field pluginField = mmoItemsClass.getField("plugin");
            Object plugin = pluginField.get(null);

            Class<?> typeClass = Class.forName("net.Indyuce.mmoitems.api.Type");
            Method getType = typeClass.getMethod("get", String.class);
            Object type = getType.invoke(null, typeId.toUpperCase());
            if (type == null) {
                return Optional.empty();
            }

            Method getItem = plugin.getClass().getMethod("getItem", typeClass, String.class);
            Object stack = getItem.invoke(plugin, type, itemId.toUpperCase());
            return stack instanceof ItemStack item ? Optional.of(item.clone()) : Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return Optional.empty();
        }
    }
}
