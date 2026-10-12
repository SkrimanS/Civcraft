package com.avrgaming.civcraft.modern.foundation;

import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Native Paper foundation items. Supports the first playable version without
 * requiring MMOItems while leaving MMOItems-created foundations compatible.
 *
 * An item must have the server-generated PDC tag. Its material or display name
 * alone will never be enough to create a settlement.
 */
public final class FoundationItemFactory {
    private static final String KEY = "foundation-kind";

    private FoundationItemFactory() {
    }

    public enum Kind {
        CAMP("camp", Material.CAMPFIRE, "Основание лагеря"),
        CIVILIZATION("civilization", Material.WHITE_BANNER, "Знамя цивилизации"),
        TOWN("town", Material.MAP, "Основание города");

        private final String id;
        private final Material material;
        private final String displayName;

        Kind(String id, Material material, String displayName) {
            this.id = id;
            this.material = material;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public static Kind parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "camp", "лагерь" -> CAMP;
                case "civ", "civilization", "цивилизация" -> CIVILIZATION;
                case "town", "город" -> TOWN;
                default -> throw new IllegalArgumentException(
                        "Неизвестный тип основания. Доступны: camp, civilization, town.");
            };
        }
    }

    public static ItemStack create(JavaPlugin plugin, Kind kind, int quantity) {
        if (quantity < 1 || quantity > 64) {
            throw new IllegalArgumentException("Количество должно быть от 1 до 64.");
        }
        ItemStack stack = new ItemStack(kind.material, quantity);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(kind.displayName));
        meta.lore(List.of(
                Component.text("CivCraft — предмет основания"),
                Component.text("Нажмите ПКМ по блоку и следуйте подсказкам в чате")
        ));
        meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, KEY), PersistentDataType.STRING, kind.id);
        stack.setItemMeta(meta);
        return stack;
    }

    public static boolean matches(JavaPlugin plugin, ItemStack stack, Kind kind) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        String value = stack.getItemMeta().getPersistentDataContainer().get(
                new NamespacedKey(plugin, KEY), PersistentDataType.STRING);
        return kind.id.equals(value);
    }
}
