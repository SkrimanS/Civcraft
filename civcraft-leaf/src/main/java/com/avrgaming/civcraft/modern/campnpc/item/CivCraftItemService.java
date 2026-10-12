package com.avrgaming.civcraft.modern.campnpc.item;

import com.avrgaming.civcraft.modern.campnpc.reward.MmoItemsHook;
import com.avrgaming.civcraft.modern.campnpc.util.ItemsAdderHook;
import com.avrgaming.civcraft.modern.campnpc.util.Text;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class CivCraftItemService {
    private final JavaPlugin plugin;
    private final ItemsAdderHook itemsAdder = new ItemsAdderHook();
    private final MmoItemsHook mmoItems = new MmoItemsHook();
    private final Map<String, CivCraftItemDefinition> byId = new LinkedHashMap<>();
    private final NamespacedKey idKey;
    private final NamespacedKey categoryKey;
    private final NamespacedKey durabilityKey;
    private final NamespacedKey usesKey;
    private final NamespacedKey maxUsesKey;

    public CivCraftItemService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "civcraft_item_id");
        this.categoryKey = new NamespacedKey(plugin, "civcraft_item_category");
        this.durabilityKey = new NamespacedKey(plugin, "civcraft_item_durability");
        this.usesKey = new NamespacedKey(plugin, "civcraft_item_uses");
        this.maxUsesKey = new NamespacedKey(plugin, "civcraft_item_max_uses");
    }

    public void reload() {
        byId.clear();
        File root = new File(plugin.getDataFolder(), "custom-items");
        for (CivCraftItemCategory category : CivCraftItemCategory.values()) {
            File file = new File(root, category.fileName() + ".yml");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection items = config.getConfigurationSection("items");
            if (items == null) {
                continue;
            }
            for (String id : items.getKeys(false)) {
                ConfigurationSection section = items.getConfigurationSection(id);
                if (section == null) {
                    continue;
                }
                int maxUses = Math.max(1, section.getInt("uses", section.getInt("max-uses", 1)));
                double deathDamage = Math.max(0D, section.getDouble("death-damage-percent", category.deathDurability() ? 15D : 0D));
                byId.put(id.toLowerCase(Locale.ROOT), new CivCraftItemDefinition(id, category, section, maxUses, deathDamage));
            }
        }
        plugin.getLogger().info("Loaded " + byId.size() + " CivCraft custom items.");
    }

    public Optional<CivCraftItemDefinition> definition(String itemId) {
        if (itemId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(itemId.trim().toLowerCase(Locale.ROOT)));
    }

    public Optional<ItemStack> create(String itemId, int amount) {
        Optional<CivCraftItemDefinition> optional = definition(itemId);
        if (optional.isEmpty()) {
            plugin.getLogger().warning("Unknown CivCraft item id: " + itemId);
            return Optional.empty();
        }
        CivCraftItemDefinition definition = optional.get();
        ItemStack item = createBase(definition).orElseGet(() -> new ItemStack(Material.STONE));
        item.setAmount(Math.max(1, amount > 0 ? amount : definition.amount()));
        applyMeta(item, definition, 100D, definition.maxUses());
        return Optional.of(item);
    }

    public boolean isCivCraftItem(ItemStack item) {
        return itemId(item).isPresent();
    }

    public Optional<String> itemId(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return Optional.empty();
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        return id == null || id.isBlank() ? Optional.empty() : Optional.of(id);
    }

    public CivCraftItemCategory category(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return CivCraftItemCategory.CONSUMABLES;
        }
        String raw = item.getItemMeta().getPersistentDataContainer().get(categoryKey, PersistentDataType.STRING);
        return CivCraftItemCategory.from(raw);
    }

    public boolean usesDeathDurability(ItemStack item) {
        Optional<String> id = itemId(item);
        if (id.isEmpty()) {
            return false;
        }
        return definition(id.get()).map(def -> def.category().deathDurability()).orElse(category(item).deathDurability());
    }

    public boolean damageOnDeath(ItemStack item) {
        Optional<String> id = itemId(item);
        if (id.isEmpty()) {
            return false;
        }
        Optional<CivCraftItemDefinition> optional = definition(id.get());
        if (optional.isEmpty()) {
            return false;
        }
        CivCraftItemDefinition definition = optional.get();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        double durability = pdc.getOrDefault(durabilityKey, PersistentDataType.DOUBLE, 100D);
        durability = Math.max(0D, durability - definition.deathDamagePercent());
        if (durability <= 0D) {
            return true;
        }
        applyMeta(item, definition, durability, pdc.getOrDefault(usesKey, PersistentDataType.INTEGER, definition.maxUses()));
        return false;
    }

    public boolean consumeUse(ItemStack item) {
        Optional<String> id = itemId(item);
        if (id.isEmpty()) {
            return false;
        }
        Optional<CivCraftItemDefinition> optional = definition(id.get());
        if (optional.isEmpty() || optional.get().category() != CivCraftItemCategory.CONSUMABLES) {
            return false;
        }
        CivCraftItemDefinition definition = optional.get();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int uses = pdc.getOrDefault(usesKey, PersistentDataType.INTEGER, definition.maxUses());
        uses = Math.max(0, uses - 1);
        if (uses <= 0) {
            return true;
        }
        applyMeta(item, definition, pdc.getOrDefault(durabilityKey, PersistentDataType.DOUBLE, 100D), uses);
        return false;
    }

    public NamespacedKey idKey() {
        return idKey;
    }

    private Optional<ItemStack> createBase(CivCraftItemDefinition definition) {
        if (!definition.mmoType().isBlank() && !definition.mmoId().isBlank()) {
            Optional<ItemStack> item = mmoItems.create(definition.mmoType(), definition.mmoId());
            if (item.isPresent()) {
                return item;
            }
            plugin.getLogger().warning("Unable to create MMOItem " + definition.mmoType() + ":" + definition.mmoId() + " for CivCraft item " + definition.id());
        }
        if (!definition.itemsAdderId().isBlank()) {
            Optional<ItemStack> item = itemsAdder.create(definition.itemsAdderId());
            if (item.isPresent()) {
                return item;
            }
            plugin.getLogger().warning("Unable to create ItemsAdder item " + definition.itemsAdderId() + " for CivCraft item " + definition.id());
        }
        Material material = Material.matchMaterial(definition.material().toUpperCase(Locale.ROOT));
        return Optional.of(new ItemStack(material == null ? Material.STONE : material));
    }

    private void applyMeta(ItemStack item, CivCraftItemDefinition definition, double durability, int uses) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(idKey, PersistentDataType.STRING, definition.id());
        pdc.set(categoryKey, PersistentDataType.STRING, definition.category().fileName());
        pdc.set(durabilityKey, PersistentDataType.DOUBLE, Math.max(0D, Math.min(100D, durability)));
        if (definition.category() == CivCraftItemCategory.CONSUMABLES) {
            pdc.set(usesKey, PersistentDataType.INTEGER, Math.max(1, uses));
            pdc.set(maxUsesKey, PersistentDataType.INTEGER, definition.maxUses());
        }

        Map<String, String> placeholders = placeholders(definition, durability, uses);
        if (definition.section().contains("name")) {
            meta.displayName(Text.component(Text.apply(definition.name(), placeholders)));
        }
        if (definition.section().isList("lore")) {
            List<Component> lore = new ArrayList<>();
            for (String line : definition.lore()) {
                lore.add(Text.component(Text.apply(line, placeholders)));
            }
            meta.lore(lore);
        }
        if (definition.section().contains("custom-model-data")) {
            meta.setCustomModelData(definition.section().getInt("custom-model-data"));
        }
        if (definition.category().deathDurability() || definition.section().getBoolean("unbreakable", false)) {
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        }
        if (definition.section().getBoolean("glow", false)) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        for (String flag : definition.section().getStringList("flags")) {
            try {
                meta.addItemFlags(ItemFlag.valueOf(flag.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        item.setItemMeta(meta);
    }

    private Map<String, String> placeholders(CivCraftItemDefinition definition, double durability, int uses) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("id", definition.id());
        placeholders.put("item_id", definition.id());
        placeholders.put("category", definition.category().fileName());
        placeholders.put("durability", String.valueOf(Math.round(durability)));
        placeholders.put("durability_percent", String.valueOf(Math.round(durability)));
        placeholders.put("uses", String.valueOf(uses));
        placeholders.put("max_uses", String.valueOf(definition.maxUses()));
        return placeholders;
    }
}
