package com.avrgaming.civcraft.modern.campnpc;

import com.avrgaming.civcraft.modern.campnpc.bridge.CivCraftBridge;
import com.avrgaming.civcraft.modern.campnpc.fancy.FancyNpcService;
import com.avrgaming.civcraft.modern.campnpc.gui.GuiListener;
import com.avrgaming.civcraft.modern.campnpc.gui.MenuManager;
import com.avrgaming.civcraft.modern.campnpc.item.CivCraftItemListener;
import com.avrgaming.civcraft.modern.campnpc.item.CivCraftItemService;
import com.avrgaming.civcraft.modern.campnpc.lang.Lang;
import com.avrgaming.civcraft.modern.campnpc.quest.QuestManager;
import com.avrgaming.civcraft.modern.campnpc.quest.QuestProgressListener;
import com.avrgaming.civcraft.modern.campnpc.reward.CampBonusApplier;
import com.avrgaming.civcraft.modern.campnpc.reward.QuestRewardService;
import com.avrgaming.civcraft.modern.campnpc.storage.PluginStorage;
import java.io.File;
import org.bukkit.plugin.java.JavaPlugin;

public final class CampNpcMenusService {
    private final JavaPlugin plugin;
    private CivCraftBridge civCraft;
    private PluginStorage storage;
    private Lang lang;
    private CivCraftItemService customItems;
    private CampBonusApplier bonuses;
    private QuestRewardService rewards;
    private QuestManager quests;
    private MenuManager menus;
    private FancyNpcService npcs;
    private boolean started;

    public CampNpcMenusService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (started) {
            return;
        }
        started = true;
        saveDefaultFiles();

        this.lang = new Lang(plugin);
        this.civCraft = new CivCraftBridge(plugin);
        this.storage = new PluginStorage(plugin);
        this.storage.initialize();
        this.customItems = new CivCraftItemService(plugin);
        this.customItems.reload();
        this.bonuses = new CampBonusApplier(plugin, storage, civCraft);
        this.rewards = new QuestRewardService(plugin, storage, civCraft, bonuses, customItems);
        this.quests = new QuestManager(plugin, storage, civCraft, rewards, lang);
        this.menus = new MenuManager(plugin, civCraft, quests);
        this.npcs = new FancyNpcService(plugin, civCraft, menus);

        plugin.getServer().getPluginManager().registerEvents(new GuiListener(menus), plugin);
        plugin.getServer().getPluginManager().registerEvents(new QuestProgressListener(quests), plugin);
        plugin.getServer().getPluginManager().registerEvents(new CivCraftItemListener(plugin, customItems), plugin);
        bonuses.start();
        quests.reload();
        menus.reload();
        npcs.start();
        plugin.getLogger().info("CivCraft camp NPC menus enabled.");
    }

    public void shutdown() {
        if (bonuses != null) {
            bonuses.resetAllOnline();
        }
        if (npcs != null) {
            npcs.shutdown();
        }
        if (storage != null) {
            storage.close();
        }
    }

    public void reloadEverything() {
        if (!started) {
            start();
            return;
        }
        saveDefaultFiles();
        if (lang != null) {
            lang.reload();
        }
        civCraft.reload();
        if (customItems != null) {
            customItems.reload();
        }
        quests.reload();
        menus.reload();
        if (bonuses != null) {
            bonuses.applyAllOnline();
        }
        npcs.forceSync();
    }

    public CivCraftBridge civCraft() {
        return civCraft;
    }

    public QuestManager quests() {
        return quests;
    }

    public MenuManager menus() {
        return menus;
    }

    public CivCraftItemService customItems() {
        return customItems;
    }

    public Lang lang() {
        return lang;
    }

    private void saveDefaultFiles() {
        saveResourceIfMissing("menus/main.yml");
        saveResourceIfMissing("menus/info.yml");
        saveResourceIfMissing("menus/camp.yml");
        saveResourceIfMissing("menus/quests.yml");
        saveResourceIfMissing("quests.yml");
        saveResourceIfMissing("custom-items/armor.yml");
        saveResourceIfMissing("custom-items/weapons.yml");
        saveResourceIfMissing("custom-items/bows.yml");
        saveResourceIfMissing("custom-items/tools.yml");
        saveResourceIfMissing("custom-items/consumables.yml");
        saveResourceIfMissing("building-configs/example.yml");
        saveResourceIfMissing("technology-configs/example.yml");
        saveResourceIfMissing("lang/ru.yml");
    }

    private void saveResourceIfMissing(String path) {
        File file = plugin.getDataFolder().toPath().resolve(path).toFile();
        if (!file.exists()) {
            plugin.saveResource(path, false);
        }
    }
}
