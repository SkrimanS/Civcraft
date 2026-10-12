package com.avrgaming.civcraft.modern.campnpc.util;

import java.lang.reflect.Method;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

public final class ItemsAdderHook {
    private Class<?> customStackClass;
    private Method getInstance;
    private Method byItemStack;
    private Method getItemStack;
    private Method getNamespacedID;

    public boolean available() {
        if (customStackClass != null) {
            return true;
        }
        if (Bukkit.getPluginManager().getPlugin("ItemsAdder") == null) {
            return false;
        }
        try {
            customStackClass = Class.forName("dev.lone.itemsadder.api.CustomStack");
            getInstance = customStackClass.getMethod("getInstance", String.class);
            byItemStack = customStackClass.getMethod("byItemStack", ItemStack.class);
            getItemStack = customStackClass.getMethod("getItemStack");
            getNamespacedID = customStackClass.getMethod("getNamespacedID");
            return true;
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    public Optional<ItemStack> create(String namespacedId) {
        if (!available()) {
            return Optional.empty();
        }
        try {
            Object stack = getInstance.invoke(null, namespacedId);
            if (stack == null) {
                return Optional.empty();
            }
            Object item = getItemStack.invoke(stack);
            return item instanceof ItemStack itemStack ? Optional.of(itemStack.clone()) : Optional.empty();
        } catch (ReflectiveOperationException exception) {
            return Optional.empty();
        }
    }

    public Optional<String> idOf(ItemStack item) {
        if (!available() || item == null) {
            return Optional.empty();
        }
        try {
            Object custom = byItemStack.invoke(null, item);
            if (custom == null) {
                return Optional.empty();
            }
            Object id = getNamespacedID.invoke(custom);
            return id == null ? Optional.empty() : Optional.of(String.valueOf(id));
        } catch (ReflectiveOperationException exception) {
            return Optional.empty();
        }
    }
}
