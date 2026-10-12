package com.avrgaming.civcraft.modern.campnpc.quest;

import com.avrgaming.civcraft.modern.campnpc.bridge.CivCraftBridge;
import com.avrgaming.civcraft.modern.campnpc.bridge.PlayerCampInfo;
import com.avrgaming.civcraft.modern.campnpc.reward.QuestRewardService;
import com.avrgaming.civcraft.modern.campnpc.lang.Lang;
import com.avrgaming.civcraft.modern.campnpc.storage.PluginStorage;
import com.avrgaming.civcraft.modern.campnpc.util.ItemsAdderHook;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class QuestManager {
    private final JavaPlugin plugin;
    private final PluginStorage storage;
    private final CivCraftBridge civCraft;
    private final QuestRewardService rewards;
    private final Lang lang;
    private final ItemsAdderHook itemsAdder = new ItemsAdderHook();
    private final Map<Integer, List<QuestDefinition>> questsByLevel = new HashMap<>();
    private final Map<String, Long> locationProgressCooldowns = new ConcurrentHashMap<>();
    private int visibleQuestLimit = 21;

    public QuestManager(JavaPlugin plugin, PluginStorage storage, CivCraftBridge civCraft, QuestRewardService rewards, Lang lang) {
        this.plugin = plugin;
        this.storage = storage;
        this.civCraft = civCraft;
        this.rewards = rewards;
        this.lang = lang;
    }

    public void reload() {
        questsByLevel.clear();
        File file = new File(plugin.getDataFolder(), "quests.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        this.visibleQuestLimit = Math.max(1, config.getInt("settings.max-visible-per-level", config.getInt("settings.quests-per-level", 21)));

        ConfigurationSection levels = config.getConfigurationSection("levels");
        if (levels == null) {
            return;
        }
        for (String levelKey : levels.getKeys(false)) {
            int level;
            try {
                level = Integer.parseInt(levelKey);
            } catch (NumberFormatException ignored) {
                continue;
            }
            List<QuestDefinition> list = new ArrayList<>();
            ConfigurationSection questRoot = levels.getConfigurationSection(levelKey);
            if (questRoot == null) {
                continue;
            }
            for (String questId : questRoot.getKeys(false)) {
                ConfigurationSection quest = questRoot.getConfigurationSection(questId);
                if (quest == null) {
                    continue;
                }
                QuestObjective objective = QuestObjective.from(quest);
                list.add(new QuestDefinition(
                        questId,
                        quest.getString("title", questId),
                        quest.getString("description", ""),
                        level,
                        objective,
                        quest.getConfigurationSection("icon"),
                        QuestReward.listFrom(quest)
                ));
            }
            questsByLevel.put(level, list);
        }
    }

    public int visibleQuestLimit() {
        return visibleQuestLimit;
    }

    public int maxActive() {
        return visibleQuestLimit;
    }

    public long rerollCooldownMillis() {
        return 0L;
    }

    public List<QuestDefinition> availableQuests(long campId) {
        int level = Math.max(1, civCraft.getCampLevel(campId));
        List<QuestDefinition> pool = questsByLevel.getOrDefault(level, List.of());
        if (pool.size() <= visibleQuestLimit) {
            return List.copyOf(pool);
        }
        return List.copyOf(pool.subList(0, visibleQuestLimit));
    }

    public List<QuestDefinition> offerQuests(long campId) {
        return availableQuests(campId);
    }

    public List<QuestDefinition> activeQuests(long campId) {
        return availableQuests(campId);
    }

    public Optional<QuestDefinition> quest(String id) {
        for (List<QuestDefinition> list : questsByLevel.values()) {
            for (QuestDefinition quest : list) {
                if (quest.id().equals(id)) {
                    return Optional.of(quest);
                }
            }
        }
        return Optional.empty();
    }

    public QuestProgress progress(long campId, String questId) {
        try (Connection connection = storage.connect(); PreparedStatement statement = connection.prepareStatement("SELECT amount, completed, rewarded FROM camp_quest_progress WHERE camp_id = ? AND quest_id = ?")) {
            statement.setLong(1, campId);
            statement.setString(2, questId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return new QuestProgress(rs.getInt("amount"), rs.getInt("completed") != 0, rs.getInt("rewarded") != 0);
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to load quest progress: " + exception.getMessage());
        }
        return new QuestProgress(0, false, false);
    }

    public boolean selectQuest(Player player, long campId, String questId) {
        player.sendMessage(lang.msg("camp-npc.quest-selection-disabled", "&eВыбор квестов отключён. Все квесты текущего уровня лагеря доступны сразу."));
        return false;
    }

    public SubmitResult submitByClick(Player player, long campId, String questId) {
        Optional<QuestDefinition> optionalQuest = quest(questId);
        if (optionalQuest.isEmpty()) {
            return new SubmitResult(false, lang.msg("camp-npc.quest-not-found", "&cКвест не найден."));
        }
        QuestDefinition quest = optionalQuest.get();
        if (!isQuestAvailableForCamp(campId, quest)) {
            return new SubmitResult(false, lang.msg("camp-npc.quest-wrong-level", "&cЭтот квест недоступен на текущем уровне лагеря."));
        }
        if (!quest.objective().isItemDelivery()) {
            return new SubmitResult(false, lang.msg("camp-npc.quest-auto-only", "&eЭтот квест выполняется автоматически, не сдачей предметов."));
        }
        return submitItems(player, campId, quest);
    }

    public SubmitResult submitItems(Player player, long campId, QuestDefinition quest) {
        if (!isQuestAvailableForCamp(campId, quest)) {
            return new SubmitResult(false, lang.msg("camp-npc.quest-wrong-level", "&cЭтот квест недоступен на текущем уровне лагеря."));
        }
        QuestProgress progress = progress(campId, quest.id());
        if (progress.completed()) {
            return new SubmitResult(false, lang.msg("camp-npc.quest-already-completed", "&eКвест уже выполнен."));
        }
        int remaining = quest.target() - progress.amount();
        if (remaining <= 0) {
            completeQuest(player, campId, quest);
            return new SubmitResult(true, lang.msg("camp-npc.quest-completed", "&aКвест выполнен."));
        }
        int removed = removeMatching(player, quest, remaining);
        if (removed <= 0) {
            return new SubmitResult(false, lang.msg("camp-npc.quest-no-items", "&cУ тебя нет нужных предметов для сдачи."));
        }
        return addProgress(player, campId, quest, removed, "§aСдано " + removed + ".");
    }

    public void progressMobKill(Player player, String mythicMobId, EntityType entityType) {
        Optional<PlayerCampInfo> camp = civCraft.getPlayerCamp(player.getUniqueId());
        if (camp.isEmpty()) {
            return;
        }
        long campId = camp.get().campId();
        for (QuestDefinition quest : availableQuests(campId)) {
            if (quest.objective().matchesMob(mythicMobId, entityType)) {
                addProgress(player, campId, quest, 1, null);
            }
        }
    }

    public void progressLocationVisit(Player player, Location location) {
        Optional<PlayerCampInfo> camp = civCraft.getPlayerCamp(player.getUniqueId());
        if (camp.isEmpty()) {
            return;
        }
        long campId = camp.get().campId();
        long now = System.currentTimeMillis();
        for (QuestDefinition quest : availableQuests(campId)) {
            if (!quest.objective().matchesLocation(location)) {
                continue;
            }
            String key = player.getUniqueId() + ":" + campId + ":" + quest.id();
            long last = locationProgressCooldowns.getOrDefault(key, 0L);
            if (now - last < 3000L) {
                continue;
            }
            locationProgressCooldowns.put(key, now);
            addProgress(player, campId, quest, 1, null);
        }
    }

    public boolean reroll(Player player, long campId, boolean force) {
        player.sendMessage(lang.msg("camp-npc.quest-reroll-disabled", "&eОбновление квестов отключено. Квесты зависят только от уровня лагеря."));
        return false;
    }

    private boolean isQuestAvailableForCamp(long campId, QuestDefinition quest) {
        return quest.level() == Math.max(1, civCraft.getCampLevel(campId));
    }

    private SubmitResult addProgress(Player player, long campId, QuestDefinition quest, int amount, String prefixMessage) {
        if (!isQuestAvailableForCamp(campId, quest)) {
            return new SubmitResult(false, lang.msg("camp-npc.quest-wrong-level", "&cЭтот квест недоступен на текущем уровне лагеря."));
        }
        QuestProgress progress = progress(campId, quest.id());
        if (progress.completed()) {
            return new SubmitResult(false, lang.msg("camp-npc.quest-already-completed", "&eКвест уже выполнен."));
        }
        int newAmount = Math.min(quest.target(), progress.amount() + Math.max(0, amount));
        boolean completed = newAmount >= quest.target();
        try (Connection connection = storage.connect()) {
            setProgress(connection, campId, quest.id(), newAmount, completed, progress.rewarded());
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to save quest progress: " + exception.getMessage());
            return new SubmitResult(false, lang.msg("camp-npc.quest-save-error", "&cОшибка сохранения прогресса."));
        }
        if (completed) {
            completeQuest(player, campId, quest);
            return new SubmitResult(true, lang.msg("camp-npc.quest-completed", "&aКвест выполнен."));
        }
        if (prefixMessage != null) {
            return new SubmitResult(true, lang.msg("camp-npc.quest-submitted", "&aСдано {amount}. Прогресс: {progress}/{target}", "amount", String.valueOf(amount), "progress", String.valueOf(newAmount), "target", String.valueOf(quest.target())));
        }
        return new SubmitResult(true, lang.msg("camp-npc.quest-progress", "&aПрогресс: {progress}/{target}", "progress", String.valueOf(newAmount), "target", String.valueOf(quest.target())));
    }

    private void completeQuest(Player player, long campId, QuestDefinition quest) {
        if (!isQuestAvailableForCamp(campId, quest)) {
            return;
        }
        QuestProgress progress = progress(campId, quest.id());
        if (progress.rewarded()) {
            return;
        }
        try (Connection connection = storage.connect()) {
            setProgress(connection, campId, quest.id(), quest.target(), true, true);
            rewards.give(player, campId, quest);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to complete quest: " + exception.getMessage());
        }
    }

    private void setProgress(Connection connection, long campId, String questId, int amount, boolean completed, boolean rewarded) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO camp_quest_progress(camp_id, quest_id, amount, completed, rewarded)
                VALUES(?,?,?,?,?)
                ON CONFLICT(camp_id, quest_id) DO UPDATE SET
                    amount = excluded.amount,
                    completed = excluded.completed,
                    rewarded = excluded.rewarded
                """)) {
            statement.setLong(1, campId);
            statement.setString(2, questId);
            statement.setInt(3, amount);
            statement.setInt(4, completed ? 1 : 0);
            statement.setInt(5, rewarded ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private int removeMatching(Player player, QuestDefinition quest, int maxAmount) {
        int removed = 0;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && removed < maxAmount; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            boolean matches = quest.acceptedItems().stream().anyMatch(matcher -> matcher.matches(item, itemsAdder));
            if (!matches) {
                continue;
            }
            int take = Math.min(item.getAmount(), maxAmount - removed);
            item.setAmount(item.getAmount() - take);
            removed += take;
            if (item.getAmount() <= 0) {
                contents[i] = null;
            }
        }
        player.getInventory().setStorageContents(contents);
        return removed;
    }

    public record SubmitResult(boolean changed, String message) {
    }
}
