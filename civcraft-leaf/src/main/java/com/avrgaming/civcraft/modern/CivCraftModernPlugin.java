package com.avrgaming.civcraft.modern;

import com.avrgaming.civcraft.modern.api.CivCraftCampApi;
import com.avrgaming.civcraft.modern.build.ConstructionAreaProtectionListener;
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
import com.avrgaming.civcraft.modern.items.NativeLegacyItems;
import com.avrgaming.civcraft.modern.placeholder.CivCraftPlaceholderExpansion;
import com.avrgaming.civcraft.modern.research.ResearchService;
import com.avrgaming.civcraft.modern.restriction.GameplayRestrictionService;
import com.avrgaming.civcraft.modern.service.CivCraftGameService;
import com.avrgaming.civcraft.modern.service.ResidentJoinListener;
import com.avrgaming.civcraft.modern.service.SettlementMembershipService;
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
    public StorageBootstrap storage(){return storage;}
    private CivCraftEconomyService economy;
    private CivCraftGameService game;
    private LegacyStructureService legacyStructures;
    private ResearchService research;
    private TownClaimService townClaims;
    private SettlementMembershipService memberships;
    private CivCraftPlaceholderExpansion placeholderExpansion;
    private FoundationItemListener foundationItems;
    private com.avrgaming.civcraft.modern.war.WarCampItemService warCampItems;
    public boolean isWarCampPending(java.util.UUID player){return warCampItems!=null&&warCampItems.hasPending(player);}
    public boolean isFoundationPending(java.util.UUID player){return foundationItems!=null&&foundationItems.hasPending(player);}
    private TabPrefixService tabPrefixes;
    private CampService campService;
    private CivCraftCampApi campApi;
    private CampNpcMenusService campNpcMenus;
    private Lang lang;
    private GameplayRestrictionService gameplayRestrictions;
    private NativeLegacyItems nativeItems;
    private com.avrgaming.civcraft.modern.government.GovernmentService governments;
    private com.avrgaming.civcraft.modern.trade.NativeTradeService trades;
    private com.avrgaming.civcraft.modern.town.ConsumptionService consumption;
    private com.avrgaming.civcraft.modern.trade.TradeShipService tradeShips;
    public com.avrgaming.civcraft.modern.trade.TradeShipService tradeShips(){return tradeShips;}
    private com.avrgaming.civcraft.modern.diplomacy.DiplomacyService diplomacy;
    private com.avrgaming.civcraft.modern.war.WarClockService warClock;
    private com.avrgaming.civcraft.modern.war.BuildingDamageService buildingDamage;
    private com.avrgaming.civcraft.modern.war.ArrowTowerService arrowTowers;
    private com.avrgaming.civcraft.modern.war.ScoutTowerService scouts;
    public com.avrgaming.civcraft.modern.war.ScoutTowerService scouts(){return scouts;}
    public com.avrgaming.civcraft.modern.war.BuildingDamageService buildingDamage(){return buildingDamage;}
    private com.avrgaming.civcraft.modern.war.BuildingRepairService buildingRepair;
    public com.avrgaming.civcraft.modern.war.BuildingRepairService buildingRepair(){return buildingRepair;}
    public com.avrgaming.civcraft.modern.war.WarClockService warClock(){return warClock;}
    public com.avrgaming.civcraft.modern.diplomacy.DiplomacyService diplomacy(){return diplomacy;}
    public com.avrgaming.civcraft.modern.town.ConsumptionService consumption(){return consumption;}
    public com.avrgaming.civcraft.modern.trade.NativeTradeService trades(){return trades;}
    private com.avrgaming.civcraft.modern.town.TownUpgradeService townUpgrades;
    public com.avrgaming.civcraft.modern.town.TownUpgradeService townUpgrades(){return townUpgrades;}
    private com.avrgaming.civcraft.modern.town.UpkeepService upkeep;
    public com.avrgaming.civcraft.modern.town.UpkeepService upkeep(){return upkeep;}
    private com.avrgaming.civcraft.modern.town.TaxService taxes;
    public com.avrgaming.civcraft.modern.town.TaxService taxes(){return taxes;}
    private com.avrgaming.civcraft.modern.culture.BiomeSurveyService biomeSurvey;
    public com.avrgaming.civcraft.modern.culture.BiomeSurveyService biomeSurvey(){return biomeSurvey;}
    private com.avrgaming.civcraft.modern.culture.CultureService culture;
    private boolean constructionClockReady;
    public com.avrgaming.civcraft.modern.culture.CultureService culture(){return culture;}
    public com.avrgaming.civcraft.modern.government.GovernmentService governments(){return governments;}
    public LegacyStructureService legacyStructures(){return legacyStructures;}
    private com.avrgaming.civcraft.modern.war.WarPvpService warPvp;
    public com.avrgaming.civcraft.modern.war.WarPvpService warPvp(){return warPvp;}
    private com.avrgaming.civcraft.modern.war.WarRespawnService warRespawns;
    public com.avrgaming.civcraft.modern.war.WarRespawnService warRespawns(){return warRespawns;}
    private com.avrgaming.civcraft.modern.war.HallValidationService hallValidation;
    public com.avrgaming.civcraft.modern.war.HallValidationService hallValidation(){return hallValidation;}
    private com.avrgaming.civcraft.modern.war.WarCampPlacement warCampPlacement;
    public com.avrgaming.civcraft.modern.war.WarCampPlacement warCampPlacement(){return warCampPlacement;}


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
        if (!storage.isReady()) {
            getLogger().severe("CivCraft cannot start without a working database.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        storage.warmupAsync();
        getServer().getScheduler().runTaskTimer(this,()->{
            try {
                if(!constructionClockReady) {
                    storage.transaction(db->{com.avrgaming.civcraft.modern.build.ConstructionClockStore.resume(db,System.currentTimeMillis());return null;});
                    governments.refreshQueuedProduction();constructionClockReady=true;
                }else storage.query(db->{com.avrgaming.civcraft.modern.build.ConstructionClockStore.checkpoint(db,System.currentTimeMillis());return null;});
            }catch(java.sql.SQLException failure){
                getLogger().severe("Unable to checkpoint construction clocks: "+failure.getMessage());
                if(!constructionClockReady)getServer().shutdown();
            }
        },1L,20L);

        this.economy = new CivCraftEconomyService(this, storage, settings);
        try {
            this.nativeItems = new NativeLegacyItems(this, storage);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Failed to initialize original CivCraft items and recipes", exception);
        }
        this.game = new CivCraftGameService(storage, settings, economy);
        new com.avrgaming.civcraft.modern.items.LegacyRecipeBook(this, nativeItems);
        new com.avrgaming.civcraft.modern.items.LegacyCombatListener(this, nativeItems);
        new com.avrgaming.civcraft.modern.items.LegacyStarterKit(this, nativeItems);
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
        storage.setWonderBonuses(com.avrgaming.civcraft.modern.wonder.WonderBonuses.load(getDataFolder().toPath().resolve("civcraft/data/buffs.yml").toFile()));
        var gardenHealing=new com.avrgaming.civcraft.modern.wonder.HangingGardensHealing(this,storage);
        getServer().getScheduler().runTaskTimer(this,gardenHealing::tick,20L,20L);
        try {
            this.governments=new com.avrgaming.civcraft.modern.government.GovernmentService(this,storage);
        } catch(java.sql.SQLException exception) {
            throw new IllegalStateException("Unable to initialize governments",exception);
        }
        getServer().getScheduler().runTaskTimer(this,governments::tick,20L*60,20L*60);
        this.research = new ResearchService(this, storage, game, settings, economy);
        this.townClaims = new TownClaimService(storage, game, settings, economy);
        this.memberships = new SettlementMembershipService(this, game, storage);
        try {
            this.trades=new com.avrgaming.civcraft.modern.trade.NativeTradeService(this,storage,nativeItems,economy);
            this.diplomacy=new com.avrgaming.civcraft.modern.diplomacy.DiplomacyService(this,storage);
            this.warClock=new com.avrgaming.civcraft.modern.war.WarClockService(this,storage);
            this.buildingDamage=new com.avrgaming.civcraft.modern.war.BuildingDamageService(this,storage);
            this.arrowTowers=new com.avrgaming.civcraft.modern.war.ArrowTowerService(this,storage);
            this.scouts=new com.avrgaming.civcraft.modern.war.ScoutTowerService(this,storage);
            var buildingRegeneration=new com.avrgaming.civcraft.modern.war.BuildingRegenerationService(this,storage);
            getServer().getScheduler().runTaskTimer(this,buildingRegeneration::tick,100L,100L);
            warPvp=new com.avrgaming.civcraft.modern.war.WarPvpService(this,storage);
            var controlPoints=new com.avrgaming.civcraft.modern.war.ControlPointService(this,storage);
            getServer().getScheduler().runTaskTimer(this,controlPoints::tick,80L,20L);
            hallValidation=new com.avrgaming.civcraft.modern.war.HallValidationService(this,storage);
            getServer().getScheduler().runTaskTimer(this,hallValidation::tick,100L,1L);
            warRespawns=new com.avrgaming.civcraft.modern.war.WarRespawnService(this,storage);
            warCampPlacement=new com.avrgaming.civcraft.modern.war.WarCampPlacement(this,storage);
            var warCampWorld=new com.avrgaming.civcraft.modern.war.WarCampWorldService(this,storage);
            getServer().getScheduler().runTaskTimer(this,warCampWorld::tick,100L,1L);
            warCampItems=new com.avrgaming.civcraft.modern.war.WarCampItemService(this,storage);
            getServer().getScheduler().runTaskTimer(this,warRespawns::tick,100L,20L);
            var ruins=new com.avrgaming.civcraft.modern.war.BuildingRuinService(this,storage);
            getServer().getScheduler().runTaskTimer(this,ruins::tick,100L,1L);
            buildingRepair=new com.avrgaming.civcraft.modern.war.BuildingRepairService(this,storage);
            getServer().getScheduler().runTaskTimer(this,buildingRepair::tick,100L,1L);
            getServer().getScheduler().runTaskTimer(this,warClock::tick,20L,20L);
            getServer().getScheduler().runTaskTimer(this,diplomacy::tick,20L,20L);
            this.townUpgrades=new com.avrgaming.civcraft.modern.town.TownUpgradeService(this,storage);
            storage.transaction(db->{com.avrgaming.civcraft.modern.trade.TradeDepositStore.install(db);return null;});
            new com.avrgaming.civcraft.modern.trade.TradeGoodWorldService(this,storage);
            var tradeShipChests=new com.avrgaming.civcraft.modern.trade.TradeShipChestService(this,storage);
            try {tradeShips=new com.avrgaming.civcraft.modern.trade.TradeShipService(this,storage,tradeShipChests);}
            catch(java.io.IOException failure){throw new IllegalStateException("Unable to initialize trade ships",failure);}
            townClaims.useLevels(townUpgrades.catalog(),org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.File(getDataFolder(),"civcraft/data/civ.yml")).getInt("civ.min_distance",15));
            this.upkeep=new com.avrgaming.civcraft.modern.town.UpkeepService(this,storage);
            getServer().getScheduler().runTaskTimer(this,upkeep::reconcileWonders,20L,20L);
            getServer().getScheduler().runTaskTimer(this,upkeep::refreshScores,20L*60,20L*60);
            this.taxes=new com.avrgaming.civcraft.modern.town.TaxService(this,storage);
            new com.avrgaming.civcraft.modern.town.PlotService(this,storage,economy);
            this.biomeSurvey=new com.avrgaming.civcraft.modern.culture.BiomeSurveyService(this,storage);
            this.culture=new com.avrgaming.civcraft.modern.culture.CultureService(this,storage);
            upkeep.refreshScores();
            storage.setCultureBiomes(biomeSurvey.catalog());
            storage.transaction(db->{
                boolean found=false;try(var s=db.createStatement();var r=s.executeQuery("PRAGMA table_info(towns)")){while(r.next())if(r.getString("name").equals("base_unhappiness"))found=true;}
                if(!found)try(var s=db.createStatement()){s.executeUpdate("ALTER TABLE towns ADD COLUMN base_unhappiness REAL NOT NULL DEFAULT 0");}return null;
            });
            storage.transaction(db->{
                boolean found=false;try(var s=db.createStatement();var r=s.executeQuery("PRAGMA table_info(towns)")){while(r.next())if(r.getString("name").equals("base_growth"))found=true;}
                if(!found)try(var s=db.createStatement()){s.executeUpdate("ALTER TABLE towns ADD COLUMN base_growth REAL NOT NULL DEFAULT 0");}return null;
            });
            storage.setHappinessRules(new com.avrgaming.civcraft.modern.town.HappinessRules(new java.io.File(getDataFolder(),"civcraft/data/happiness.yml")));
        } catch(java.sql.SQLException exception) {
            throw new IllegalStateException("Unable to initialize native trading",exception);
        }
        int dailyTickSeconds=Integer.getInteger("civcraft.test.daily.tickSeconds",60);
        if(dailyTickSeconds<1||dailyTickSeconds>60)throw new IllegalArgumentException("Invalid daily test tick period");
        getServer().getScheduler().runTaskTimer(this,townUpgrades::tick,20L*dailyTickSeconds,20L*dailyTickSeconds);
        getServer().getScheduler().runTaskTimer(this,culture::tick,40L,20L);
        try {
            var cultureClock=new com.avrgaming.civcraft.modern.culture.CultureClock(this,storage);
            int scienceSeconds=Integer.getInteger("civcraft.test.culture.minuteSeconds",60);
            if(scienceSeconds<1 || scienceSeconds>60)throw new IllegalArgumentException("Invalid science test interval");
            getServer().getScheduler().runTaskTimer(this,cultureClock::minute,20L*scienceSeconds,20L*scienceSeconds);
            getServer().getScheduler().runTaskTimer(this,cultureClock::tick,40L,20L);
            consumption=new com.avrgaming.civcraft.modern.town.ConsumptionService(this,storage);
            getServer().getScheduler().runTaskTimer(this,consumption::tick,80L,20L);
        }catch(java.sql.SQLException|java.io.IOException failure){throw new IllegalStateException("Unable to initialize culture and building clocks",failure);}
        // Accrue idle science before research can complete in this same minute.
        getServer().getScheduler().runTaskTimer(this,research::tick,20L*60L,20L*60L);
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
        getServer().getPluginManager().registerEvents(new ConstructionAreaProtectionListener(this, storage), this);
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

        CivCraftCommand executor = new CivCraftCommand(this, game, legacyStructures, research, townClaims, memberships, economy, campService, campNpcMenus, lang);
        for (String commandName : List.of("camp", "civ", "town", "build", "civadmin", "research")) {
            var command = getCommand(commandName);
            if (command != null) {
                command.setExecutor(executor);
                command.setTabCompleter(executor);
            }
        }

        // Plugins using STARTUP loading run before worlds are available; defer
        // restart recovery until normal server ticks begin.
        getServer().getScheduler().runTaskLater(this, legacyStructures::recoverInterruptedBuilds, 40L);
        getServer().getScheduler().runTaskLater(this,legacyStructures::restoreCompletedChestMarkers,60L);
        getLogger().info("CivCraft Paper 1.21.11 port foundation enabled.");
    }

    @Override
    public void onDisable() {
        if(arrowTowers!=null)arrowTowers.close();
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
            if(constructionClockReady && storage.isReady())try{storage.query(db->{com.avrgaming.civcraft.modern.build.ConstructionClockStore.checkpoint(db,System.currentTimeMillis());return null;});}
            catch(java.sql.SQLException failure){getLogger().warning("Unable to save construction shutdown checkpoint: "+failure.getMessage());}
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

    public NativeLegacyItems nativeItems() { return nativeItems; }

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
