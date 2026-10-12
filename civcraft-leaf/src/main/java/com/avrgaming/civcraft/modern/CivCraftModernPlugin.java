package com.avrgaming.civcraft.modern;

import com.avrgaming.civcraft.modern.api.CivCraftCampApi;
import com.avrgaming.civcraft.modern.build.LegacyStructureService;
import com.avrgaming.civcraft.modern.build.ProtectedBlockListener;
import com.avrgaming.civcraft.modern.build.StructurePreviewChatListener;
import com.avrgaming.civcraft.modern.camp.CampListener;
import com.avrgaming.civcraft.modern.camp.CampService;
import com.avrgaming.civcraft.modern.campnpc.CampNpcMenusService;
import com.avrgaming.civcraft.modern.campnpc.lang.Lang;
import com.avrgaming.civcraft.modern.command.CivCraftCommand;
import com.avrgaming.civcraft.modern.config.ModernCivCraftSettings;
import com.avrgaming.civcraft.modern.economy.CivCraftEconomyService;
import com.avrgaming.civcraft.modern.foundation.FoundationItemListener;
import com.avrgaming.civcraft.modern.integration.IntegrationRegistry;
import com.avrgaming.civcraft.modern.placeholder.CivCraftPlaceholderExpansion;
import com.avrgaming.civcraft.modern.research.ResearchService;
import com.avrgaming.civcraft.modern.restriction.GameplayRestrictionService;
import com.avrgaming.civcraft.modern.service.CivCraftGameService;
import com.avrgaming.civcraft.modern.service.ResidentJoinListener;
import com.avrgaming.civcraft.modern.service.TabPrefixService;
import com.avrgaming.civcraft.modern.service.TownClaimProtectionListener;
import com.avrgaming.civcraft.modern.service.TownClaimService;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.world.NoVanillaStructuresChunkGenerator;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

public final class CivCraftModernPlugin extends JavaPlugin {

    private ModernCivCraftSettings settings;
    private IntegrationRegistry integrations;
    private StorageBootstrap storage;
    private CivCraftEconomyService economy;
    private CivCraftGameService game;
    private LegacyStructureService legacyStructures;
    private ResearchService research;
    private TownClaimService townClaims;
    private CivCraftPlaceholderExpansion placeholderExpansion;
    private FoundationItemListener foundationItems;
    private TabPrefixService tabPrefixes;
    private CampService campService;
    private CivCraftCampApi campApi;
    private CampNpcMenusService campNpcMenus;
    private Lang lang;
    private GameplayRestrictionService gameplayRestrictions;


    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        getLogger().info("Using CivCraft no-vanilla-structures generator for world '" + worldName + "'.");
        return new NoVanillaStructuresChunkGenerator();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("messages.yml");
        for (String legacyConfig : List.of("arena.yml", "buffs.yml", "camp.yml", "civ.yml", "culture.yml", "enchantments.yml", "espionage.yml", "fishing.yml", "goods.yml", "governments.yml", "happiness.yml", "market.yml", "materials.yml", "nocheat.yml", "perks.yml", "randomevents.yml", "score.yml", "spawners.yml", "structures.yml", "techs.yml", "teleportsOff.txt", "teleportsOn.txt", "tips.txt", "town.yml", "units.yml", "war.txt", "war.yml", "wonders.yml")) {
            saveResourceIfMissing("civcraft/data/" + legacyConfig);
        }

        reloadModernConfig();
        saveBundledStructureTemplates();
        ensureBuildingsFolder();
        this.storage = new StorageBootstrap(this, settings);
        storage.initializeLocalStorage();
        storage.warmupAsync();

        this.economy = new CivCraftEconomyService(this, storage, settings);
        this.game = new CivCraftGameService(storage, settings, economy);
        this.tabPrefixes = new TabPrefixService(this, game);
        this.campService = new CampService(this, game, economy, settings);
        this.campService.startTasks();
        this.campApi = new CivCraftCampApi(this, settings, economy);
        this.campApi.initializeStorage();
        this.campApi.startNpcMarkerScanner();
        this.campNpcMenus = new CampNpcMenusService(this);
        this.campNpcMenus.start();
        this.lang = this.campNpcMenus.lang();
        this.gameplayRestrictions = new GameplayRestrictionService(this, settings);
        this.gameplayRestrictions.start();
        this.legacyStructures = new LegacyStructureService(this, storage, settings, economy, game);
        this.research = new ResearchService(this, storage, game, settings, economy);
        this.townClaims = new TownClaimService(storage, game, settings, economy);
        getServer().getScheduler().runTaskTimer(this, research::tick, 20L * 60L, 20L * 60L);
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (!storage.isReady() || !getConfig().getBoolean("economy.production.enabled", true)) {
                return;
            }
            try {
                storage.distributeTownIncomeMinute();
            } catch (java.sql.SQLException | ArithmeticException exception) {
                getLogger().warning("CivCraft town income tick failed: " + exception.getMessage());
            }
        }, 20L * 60L, 20L * 60L);
        getServer().getPluginManager().registerEvents(new ResidentJoinListener(this, game, tabPrefixes), this);
        getServer().getPluginManager().registerEvents(new TownClaimProtectionListener(this, townClaims), this);
        getServer().getPluginManager().registerEvents(new ProtectedBlockListener(this, storage, lang), this);
        this.foundationItems = new FoundationItemListener(this, game, legacyStructures, tabPrefixes, settings, lang);
        getServer().getPluginManager().registerEvents(foundationItems, this);
        getServer().getPluginManager().registerEvents(new StructurePreviewChatListener(this, legacyStructures, lang), this);
        getServer().getPluginManager().registerEvents(new CampListener(this, campService), this);

        this.integrations = new IntegrationRegistry(this, settings);
        integrations.detect();

        if (settings.placeholderApiEnabled() && getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            this.placeholderExpansion = new CivCraftPlaceholderExpansion(this, settings, game, research, townClaims, economy, campService);
            placeholderExpansion.register();
        }

        CivCraftCommand executor = new CivCraftCommand(this, game, legacyStructures, research, townClaims, economy, campService, campNpcMenus, lang);
        for (String commandName : List.of("camp", "civ", "town", "build", "civadmin", "research")) {
            var command = getCommand(commandName);
            if (command != null) {
                command.setExecutor(executor);
                command.setTabCompleter(executor);
            }
        }

        getLogger().info("CivCraft Paper 1.21.11 port foundation enabled.");
    }

    @Override
    public void onDisable() {
        if (foundationItems != null) {
            foundationItems.shutdown();
        }
        if (legacyStructures != null) {
            legacyStructures.cancelAllPreviews();
        }
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
        }
        if (campNpcMenus != null) {
            campNpcMenus.shutdown();
        }
        if (storage != null) {
            storage.close();
        }
    }

    public void reloadModernConfig() {
        reloadConfig();
        this.settings = ModernCivCraftSettings.from(getConfig());
        if (foundationItems != null) {
            foundationItems.updateSettings(settings);
        }
        if (campService != null) {
            campService.updateSettings(settings);
        }
        if (campApi != null) {
            campApi.updateSettings(settings);
        }
        if (campNpcMenus != null) {
            campNpcMenus.reloadEverything();
            this.lang = campNpcMenus.lang();
        }
        if (gameplayRestrictions != null) {
            gameplayRestrictions.reload(settings);
        }
    }

    public void reloadIntegrations() {
        this.integrations = new IntegrationRegistry(this, settings);
        integrations.detect();
    }

    public ModernCivCraftSettings settings() {
        return settings;
    }

    public IntegrationRegistry integrations() {
        return integrations;
    }

    public CivCraftCampApi getCampApi() {
        return campApi;
    }

    public Lang lang() {
        return lang;
    }

    private void saveBundledStructureTemplates() {
        // Template manifest is part of the JAR. Bundled assets require no external
        // downloads and are copied only once; customized server files are retained.
        try (InputStream manifest = getResource("legacy-templates/manifest.txt")) {
            if (manifest == null) {
                getLogger().warning("CivCraft bundled structure manifest is missing.");
                return;
            }
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(manifest, StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    String path = line.trim();
                    if (path.isEmpty() || path.startsWith("#")) {
                        continue;
                    }
                    if (!path.matches("[a-z0-9_/-]+\\.def") || path.contains("..")) {
                        getLogger().warning("Ignoring unsafe bundled template path: " + path);
                        continue;
                    }
                    saveResourceIfMissing("legacy-templates/" + path);
                }
            }
        } catch (IOException exception) {
            getLogger().severe("Unable to unpack bundled CivCraft structure templates: " + exception.getMessage());
        }
    }

    private void ensureBuildingsFolder() {
        try {
            Files.createDirectories(Path.of(settings.faweSchematicsRoot()).toAbsolutePath().normalize());
        } catch (IOException exception) {
            getLogger().warning("Unable to create buildings folder: " + exception.getMessage());
        }
    }

    private void saveResourceIfMissing(String path) {
        if (!getDataFolder().toPath().resolve(path).toFile().exists()) {
            saveResource(path, false);
        }
    }
}
