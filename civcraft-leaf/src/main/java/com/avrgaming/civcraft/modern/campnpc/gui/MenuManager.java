package com.avrgaming.civcraft.modern.campnpc.gui;

import com.avrgaming.civcraft.modern.campnpc.bridge.CampRole;
import com.avrgaming.civcraft.modern.campnpc.bridge.CivCraftBridge;
import com.avrgaming.civcraft.modern.campnpc.quest.QuestDefinition;
import com.avrgaming.civcraft.modern.campnpc.quest.QuestManager;
import com.avrgaming.civcraft.modern.campnpc.quest.QuestProgress;
import com.avrgaming.civcraft.modern.campnpc.util.Text;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public final class MenuManager {
    private final JavaPlugin plugin;
    private final CivCraftBridge civCraft;
    private final QuestManager quests;
    private final ItemBuilder itemBuilder = new ItemBuilder();
    private final Map<String, YamlConfiguration> menuFiles = new HashMap<>();
    private final Map<String, GuiAction> slotActions = new HashMap<>();
    private String noAccessMessage;
    private String onlyLeaderMessage;

    public MenuManager(JavaPlugin plugin, CivCraftBridge civCraft, QuestManager quests) {
        this.plugin = plugin;
        this.civCraft = civCraft;
        this.quests = quests;
    }

    public void reload() {
        menuFiles.clear();
        load("main");
        load("info");
        load("camp");
        load("quests");
        noAccessMessage = plugin.getConfig().getString("messages.no-access", "§cЭтот NPC доступен только участникам лагеря.");
        onlyLeaderMessage = plugin.getConfig().getString("messages.only-leader", "§cВыбирать квесты может только лидер лагеря.");
    }

    public void openMain(Player player, long campId, String campName) {
        CampRole role = civCraft.getCampRole(player.getUniqueId(), campId);
        if (!role.isMember()) {
            player.sendMessage(noAccessMessage);
            return;
        }
        openStatic(player, new GuiContext(player, campId, campName, role), GuiType.MAIN, "main");
    }

    public void openStatic(Player player, GuiContext context, GuiType type, String id) {
        YamlConfiguration config = menuFiles.get(id);
        if (config == null) {
            player.sendMessage("§cMenu not found: " + id);
            return;
        }
        MenuConfig menu = MenuConfig.from(id, config, itemBuilder, context.placeholders());
        GuiHolder holder = new GuiHolder(type, context);
        Inventory inventory = Bukkit.createInventory(holder, menu.size, Text.component(Text.apply(menu.title, context.placeholders())));
        for (MenuItem item : menu.items) {
            for (int slot : item.slots()) {
                if (slot < 0 || slot >= inventory.getSize()) {
                    continue;
                }
                inventory.setItem(slot, item.item().clone());
                if (!item.actions().isEmpty()) {
                    slotActions.put(key(player, inventory, slot), item.actions().getFirst());
                }
            }
        }
        player.openInventory(inventory);
    }

    public void openQuests(Player player, GuiContext context) {
        openQuestList(player, context);
    }

    public void openActiveQuests(Player player, GuiContext context) {
        openQuestList(player, context);
    }

    public void openQuestList(Player player, GuiContext context) {
        YamlConfiguration config = menuFiles.get("quests");
        int size = Math.max(9, Math.min(54, ((config.getInt("size", 54) + 8) / 9) * 9));
        GuiHolder holder = new GuiHolder(GuiType.QUESTS, context);
        Inventory inventory = Bukkit.createInventory(holder, size, Text.component(Text.apply(config.getString("title", "&6Квесты лагеря"), context.placeholders())));
        renderConfiguredItems(player, inventory, config, context);

        List<Integer> slots = config.getIntegerList("quest-slots");
        List<QuestDefinition> active = quests.availableQuests(context.campId());
        for (int i = 0; i < Math.min(slots.size(), active.size()); i++) {
            QuestDefinition quest = active.get(i);
            int slot = slots.get(i);
            if (slot < 0 || slot >= size) {
                continue;
            }
            inventory.setItem(slot, questItem(quest, context, false));
            slotActions.put(key(player, inventory, slot), new GuiAction("SUBMIT_QUEST", quest.id()));
        }
        player.openInventory(inventory);
    }

    public void openQuestSelect(Player player, GuiContext context) {
        openQuestList(player, context);
    }

    public void handleClick(Player player, Inventory inventory, int slot, ClickType click) {
        if (!(inventory.getHolder() instanceof GuiHolder holder)) {
            return;
        }
        GuiAction action = slotActions.get(key(player, inventory, slot));
        if (action == null) {
            return;
        }
        GuiContext context = holder.context();
        switch (action.type()) {
            case "CLOSE" -> player.closeInventory();
            case "OPEN_MENU" -> openNamed(player, context, action.value());
            case "QUESTS" -> openQuestList(player, context);
            case "SELECT_QUEST", "REROLL_QUESTS" -> openQuestList(player, context);
            case "SUBMIT_QUEST" -> {
                QuestManager.SubmitResult result = quests.submitByClick(player, context.campId(), action.value());
                player.sendMessage(result.message());
                openQuestList(player, context);
            }
            default -> {
            }
        }
    }

    public void cleanup(Player player, Inventory inventory) {
        String prefix = player.getUniqueId() + ":" + System.identityHashCode(inventory) + ":";
        slotActions.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private void openNamed(Player player, GuiContext context, String value) {
        switch (value.toLowerCase()) {
            case "main" -> openStatic(player, context, GuiType.MAIN, "main");
            case "info" -> openStatic(player, context, GuiType.INFO, "info");
            case "camp" -> openStatic(player, context, GuiType.CAMP, "camp");
            case "quests" -> openQuests(player, context);
            case "quest-select" -> openQuestList(player, context);
            default -> openStatic(player, context, GuiType.MAIN, "main");
        }
    }

    private void renderConfiguredItems(Player player, Inventory inventory, YamlConfiguration config, GuiContext context) {
        MenuConfig menu = MenuConfig.from("dynamic", config, itemBuilder, context.placeholders());
        for (MenuItem item : menu.items) {
            for (int slot : item.slots()) {
                if (slot < 0 || slot >= inventory.getSize()) {
                    continue;
                }
                inventory.setItem(slot, item.item().clone());
                if (!item.actions().isEmpty()) {
                    slotActions.put(key(player, inventory, slot), item.actions().getFirst());
                }
            }
        }
    }

    private ItemStack questItem(QuestDefinition quest, GuiContext context, boolean selectMenu) {
        Map<String, String> placeholders = new HashMap<>(context.placeholders());
        QuestProgress progress = quests.progress(context.campId(), quest.id());
        placeholders.put("quest_id", quest.id());
        placeholders.put("quest_title", quest.title());
        placeholders.put("quest_description", quest.description());
        placeholders.put("progress", String.valueOf(progress.amount()));
        placeholders.put("target", String.valueOf(quest.target()));
        placeholders.put("objective_type", quest.objective().type());
        placeholders.put("quest_coordinates", quest.objective().locationSummary());
        placeholders.put("coordinates", quest.objective().locationSummary());
        placeholders.put("quest_locations", quest.objective().locationSummary());
        placeholders.put("status", progress.completed() ? "§aВыполнено" : "§eВ процессе");
        ConfigurationSection icon = quest.icon();
        ItemStack item = icon == null ? new ItemStack(Material.PAPER) : itemBuilder.build(icon, placeholders);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (icon == null || !icon.contains("name")) {
                meta.displayName(Text.component("§e" + quest.title()));
            }
            if (icon == null || !icon.isList("lore")) {
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                lore.add(Text.component("§7" + quest.description()));
                lore.add(Text.component("§7Собрано: §e" + progress.amount() + "/" + quest.target()));
                if (selectMenu) {
                    lore.add(Text.component("§aЛКМ, чтобы выбрать"));
                } else if (quest.objective().isItemDelivery()) {
                    lore.add(Text.component("§aЛКМ, чтобы сдать предметы"));
                } else if (quest.objective().isMobKill()) {
                    lore.add(Text.component("§7Убийства засчитываются автоматически."));
                } else if (quest.objective().isLocationVisit()) {
                    lore.add(Text.component("§7Координаты: §e" + quest.objective().locationSummary()));
                    lore.add(Text.component("§7Посещение точки засчитывается автоматически."));
                }
                meta.lore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private String key(Player player, Inventory inventory, int slot) {
        return player.getUniqueId() + ":" + System.identityHashCode(inventory) + ":" + slot;
    }

    private void load(String id) {
        menuFiles.put(id, YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "menus/" + id + ".yml")));
    }
}
