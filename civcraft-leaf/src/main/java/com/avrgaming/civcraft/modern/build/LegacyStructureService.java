package com.avrgaming.civcraft.modern.build;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.campnpc.lang.Lang;
import com.avrgaming.civcraft.modern.config.ModernCivCraftSettings;
import com.avrgaming.civcraft.modern.domain.ResidentProfile;
import com.avrgaming.civcraft.modern.domain.TownRecord;
import com.avrgaming.civcraft.modern.economy.CivCraftEconomyService;
import com.avrgaming.civcraft.modern.service.CivCraftGameService;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public final class LegacyStructureService {
    private final JavaPlugin plugin;
    private final StorageBootstrap storage;
    private final CivCraftEconomyService economy;
    private final CivCraftGameService game;
    private final LegacyDefTemplateLoader loader = new LegacyDefTemplateLoader();
    private final Map<Path, LegacyDefTemplate> templateCache = new ConcurrentHashMap<>();
    private final Map<Path, WorldEditSchematicTemplate> schematicCache = new ConcurrentHashMap<>();
    private final Map<UUID, PendingStructurePreview> pendingPreviews = new ConcurrentHashMap<>();
    private final StructureDefinitionService definitions;
    private final BuildQueue buildQueue;
    private ModernCivCraftSettings settings;
    private Lang fallbackLang;

    public LegacyStructureService(JavaPlugin plugin, StorageBootstrap storage, ModernCivCraftSettings settings, CivCraftEconomyService economy, CivCraftGameService game) {
        this.plugin = plugin;
        this.storage = storage;
        this.economy = economy;
        this.game = game;
        this.settings = settings;
        this.definitions = new StructureDefinitionService(plugin, settings);
        this.buildQueue = new BuildQueue(plugin, settings.maxStructureBlocksPerTick());
        warmupTemplateCacheAsync();
    }

    public void updateSettings(ModernCivCraftSettings settings) {
        this.settings = settings;
        this.definitions.updateSettings(settings);
        this.buildQueue.updateBudget(settings.maxStructureBlocksPerTick());
    }

    public List<StructureDefinition> listDefinitions(int limit) {
        return definitions.list(limit);
    }

    public List<String> listTemplates(String type, int limit) throws IOException {
        Path root = templateRoot().resolve("themes").resolve(settings.legacyDefaultTheme()).resolve(type);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .limit(limit)
                    .toList();
        }
    }

    public QueuedLegacyBuild paste(Player player, String type, String templateName, String direction) throws IOException, SQLException {
        if (!settings.legacyTemplatesEnabled()) {
            throw new IllegalArgumentException("Legacy .def templates are disabled in config.");
        }
        TownRecord town = requireMayorTown(player);
        String cleanType = normalize(type);
        String cleanTemplate = normalize(templateName);
        String cleanDirection = normalizeRotationDirection(direction);
        Path templatePath = resolveTemplate(cleanType, cleanTemplate, cleanDirection);
        LegacyDefTemplate template = loadTemplate(templatePath);
        Location origin = nextChunkOrigin(player, player.getLocation(), template.sizeX(), template.sizeZ());
        validateStructureHeight(origin, template.sizeY());
        return startBuild(player, town, cleanType, cleanTemplate, cleanDirection, templatePath, template, null, cleanTemplate, 0.0, 1, 0.0, 0.0, 0.0, 0.0, 0, origin, true);
    }

    public QueuedLegacyBuild buildStructure(Player player, String structureId, String direction) throws IOException, SQLException {
        TownRecord town = requireMayorTown(player);
        return buildStructureForTown(player, town, structureId, direction, player.getLocation(), null, true);
    }

    public void previewStructure(Player player, String structureId, String direction) throws IOException, SQLException {
        previewStructure(player, structureId, player.getLocation().getBlockY(), direction);
    }

    public void previewStructure(Player player, String structureId, int y, String direction) throws IOException, SQLException {
        TownRecord town = requireMayorTown(player);
        StructureDefinition definition = definition(structureId);
        String cleanDirection = normalizeRotationDirection(direction);
        ResolvedBuildTemplate resolved = resolveBuildTemplate(definition, cleanDirection);
        Location requested = player.getLocation().clone();
        requested.setY(y);
        Location origin = nextChunkOrigin(player, requested, resolved.sizeX(), resolved.sizeZ());
        validateStructureHeight(origin, resolved.sizeY());

        cancelPreview(player, true);

        PendingStructurePreview preview = new PendingStructurePreview(
                player.getUniqueId(),
                town.id(),
                definition.id(),
                cleanDirection,
                origin,
                resolved.sizeX(),
                resolved.sizeY(),
                resolved.sizeZ(),
                false
        );
        if (resolved.schematic() == null) {
            throw new IllegalArgumentException("Для обычных построек теперь используется только .schem через FAWE. Файл не найден: " + definition.id());
        }
        pendingPreviews.put(player.getUniqueId(), preview);
        sendPhantomPreview(player, preview, resolved.schematic());
    }

    public Location previewFoundationStructure(Player player, String structureId, Location location, String direction) throws IOException {
        StructureDefinition definition = definition(structureId);
        String cleanDirection = normalizeRotationDirection(direction);
        ResolvedBuildTemplate resolved = resolveBuildTemplate(definition, cleanDirection);
        if (resolved.schematic() == null) {
            throw new IllegalArgumentException("Для основания нужен .schem файл постройки: " + definition.id());
        }
        Location origin = nextChunkOrigin(player, location, resolved.sizeX(), resolved.sizeZ());
        validateStructureHeight(origin, resolved.sizeY());
        cancelPreview(player, true);
        PendingStructurePreview preview = new PendingStructurePreview(
                player.getUniqueId(),
                0L,
                definition.id(),
                cleanDirection,
                origin,
                resolved.sizeX(),
                resolved.sizeY(),
                resolved.sizeZ(),
                true
        );
        pendingPreviews.put(player.getUniqueId(), preview);
        sendPhantomPreview(player, preview, resolved.schematic());
        return origin.clone();
    }

    public boolean isFoundationPreview(Player player) {
        PendingStructurePreview preview = pendingPreviews.get(player.getUniqueId());
        return preview != null && preview.foundation;
    }

    public QueuedLegacyBuild buildAdminLegacy(Player player, String templateName, int y, String direction) throws IOException, SQLException {
        String cleanTemplate = normalize(templateName.replace(".def", ""));
        String cleanDirection = normalizeRotationDirection(direction);
        Path templatePath = resolveTemplate("structures", cleanTemplate, cleanDirection);
        LegacyDefTemplate template = loadTemplate(templatePath);
        Location requested = player.getLocation().clone();
        requested.setY(y);
        Location origin = nextChunkOrigin(player, requested, template.sizeX(), template.sizeZ());
        validateStructureHeight(origin, template.sizeY());
        return startBuild(player, null, "legacy-admin", cleanTemplate, cleanDirection, templatePath, template, null, cleanTemplate, 0.0, 1, 0.0, 0.0, 0.0, 0.0, 0, origin, true);
    }

    public boolean hasPreview(Player player) {
        return pendingPreviews.containsKey(player.getUniqueId());
    }

    public QueuedLegacyBuild confirmPreview(Player player) throws IOException, SQLException {
        PendingStructurePreview preview = pendingPreviews.remove(player.getUniqueId());
        if (preview == null) {
            throw new IllegalArgumentException("У вас нет активного превью постройки.");
        }
        restorePhantomPreview(player, preview);
        TownRecord town = requireMayorTown(player);
        if (town.id() != preview.townId) {
            throw new IllegalArgumentException("Превью было создано для другого города.");
        }
        return buildStructureForTown(player, town, preview.structureId, preview.direction, preview.origin, null, true);
    }

    public void cancelPreview(Player player) {
        cancelPreview(player, false);
    }

    public void cancelPreview(Player player, boolean silent) {
        PendingStructurePreview preview = pendingPreviews.remove(player.getUniqueId());
        if (preview == null) {
            return;
        }
        restorePhantomPreview(player, preview);
        if (!silent) {
            player.sendMessage(lang().component("build.preview-cancelled", "&eПревью постройки отменено."));
        }
    }

    public void cancelAllPreviews() {
        for (UUID uuid : List.copyOf(pendingPreviews.keySet())) {
            Player player = plugin.getServer().getPlayer(uuid);
            PendingStructurePreview preview = pendingPreviews.remove(uuid);
            if (player != null && preview != null) {
                restorePhantomPreview(player, preview);
            }
        }
    }

    public QueuedLegacyBuild buildCampStructure(Player player, Location location) throws IOException, SQLException {
        StructureDefinition definition = definition(settings.campStructureId());
        return buildDefinedStructure(player, null, definition, settings.legacyDefaultDirection(), location, 0.0, false);
    }

    public QueuedLegacyBuild buildCampStructureAtOrigin(Player player, Location origin) throws IOException, SQLException {
        StructureDefinition definition = definition(settings.campStructureId());
        return buildDefinedStructureAtOrigin(player, null, definition, settings.legacyDefaultDirection(), origin, 0.0, false);
    }

    public QueuedLegacyBuild buildCapitalStructureAtOrigin(Player player, TownRecord town, Location origin) throws IOException, SQLException {
        StructureDefinition definition = definition(settings.capitolStructureId());
        return buildDefinedStructureAtOrigin(player, town, definition, settings.legacyDefaultDirection(), origin, 0.0, false);
    }

    public QueuedLegacyBuild buildTownHallStructureAtOrigin(Player player, TownRecord town, Location origin) throws IOException, SQLException {
        StructureDefinition definition = definition(settings.townHallStructureId());
        return buildDefinedStructureAtOrigin(player, town, definition, settings.legacyDefaultDirection(), origin, 0.0, false);
    }

    public QueuedLegacyBuild buildCapitalStructure(Player player, TownRecord town, Location location) throws IOException, SQLException {
        StructureDefinition definition = definition(settings.capitolStructureId());
        return buildDefinedStructure(player, town, definition, settings.legacyDefaultDirection(), location, 0.0, false);
    }

    public QueuedLegacyBuild buildTownHallStructure(Player player, TownRecord town, Location location) throws IOException, SQLException {
        StructureDefinition definition = definition(settings.townHallStructureId());
        return buildDefinedStructure(player, town, definition, settings.legacyDefaultDirection(), location, 0.0, false);
    }

    public QueuedLegacyBuild buildStructureForTown(Player player, TownRecord town, String structureId, String direction, Location location, Double forcedHammerCost, boolean requireMayor) throws IOException, SQLException {
        if (requireMayor && !town.mayorUuid().equals(player.getUniqueId())) {
            throw new IllegalArgumentException("Только мэр города может ставить постройки.");
        }
        StructureDefinition definition = definition(structureId);
        return buildDefinedStructure(player, town, definition, direction, location, forcedHammerCost == null ? definition.hammerCost() : forcedHammerCost, true);
    }

    private QueuedLegacyBuild buildDefinedStructure(Player player, TownRecord town, StructureDefinition definition, String direction, Location location, double hammerCost, boolean chargeMoney) throws IOException, SQLException {
        String cleanDirection = normalizeRotationDirection(direction);
        ResolvedBuildTemplate resolved = resolveBuildTemplate(definition, cleanDirection);
        Location origin = nextChunkOrigin(player, location, resolved.sizeX(), resolved.sizeZ());
        if (resolved.schematic() == null) {
            throw new IllegalArgumentException("Для обычных построек теперь используется только .schem через FAWE. Файл не найден: " + definition.id());
        }
        return startSchematicBuild(player, town, "structures", definition.template(), cleanDirection, resolved.path(), resolved.schematic(), definition.id(), definition.displayName(), chargeMoney ? definition.cost() : 0.0, definition.maxHitpoints(), hammerCost, definition.hammersPerHourBonus(), definition.beakersPerHour(), definition.moneyPerHour(), definition.happiness(), origin, true);
    }

    private QueuedLegacyBuild buildDefinedStructureAtOrigin(Player player, TownRecord town, StructureDefinition definition, String direction, Location origin, double hammerCost, boolean chargeMoney) throws IOException, SQLException {
        String cleanDirection = normalizeRotationDirection(direction);
        ResolvedBuildTemplate resolved = resolveBuildTemplate(definition, cleanDirection);
        if (resolved.schematic() == null) {
            throw new IllegalArgumentException("Для основания нужен .schem файл постройки: " + definition.id());
        }
        return startSchematicBuild(player, town, "structures", definition.template(), cleanDirection, resolved.path(), resolved.schematic(), definition.id(), definition.displayName(), chargeMoney ? definition.cost() : 0.0, definition.maxHitpoints(), hammerCost, definition.hammersPerHourBonus(), definition.beakersPerHour(), definition.moneyPerHour(), definition.happiness(), origin.clone(), true);
    }

    private QueuedLegacyBuild startBuild(Player player, TownRecord town, String type, String templateName, String direction, Path templatePath, LegacyDefTemplate template, String structureId, String displayName, double cost, int maxHitpoints, double totalHammers, double productionHammersPerHour, double beakersPerHour, double moneyPerHour, int happinessDelta, Location origin, boolean showPreview) throws SQLException {
        validateStructureHeight(origin, template.sizeY());
        if (town != null && town.hammersPerHour() <= 0.0) {
            throw new IllegalArgumentException("У города нет производства молотков.");
        }
        if (town != null && storage.hasActiveStructureBuild(town.id())) {
            throw new IllegalArgumentException("В городе уже строится здание. Одновременно можно строить только одно здание.");
        }
        List<StructureChunkCoord> chunks = BuildQueue.chunksFor(origin, template);
        validateChunkSolidSupport(origin, template, chunks);
        if (storage.hasStructureChunkOverlap(chunks)) {
            throw new IllegalArgumentException("Нельзя поставить постройку: один из чанков уже занят другой постройкой.");
        }

        double hammersPerHour = town == null ? 0.0 : Math.max(0.0, town.hammersPerHour());
        long startedAt = System.currentTimeMillis();
        long durationMillis = buildDurationMillis(totalHammers, hammersPerHour);
        long finishAt = durationMillis <= 0L ? startedAt : startedAt + durationMillis;

        if (cost > 0.0) {
            if (town != null) {
                storage.withdrawTown(town.id(), cost);
            } else {
                economy.withdraw(player, cost, "structure build " + (structureId == null ? templateName : structureId));
            }
        }
        long buildId = 0L;
        boolean structureRecorded = false;
        try {
            buildId = storage.recordStructureBuild(
                    player.getUniqueId(),
                    town == null ? null : town.id(),
                    type,
                    templateName,
                    direction,
                    origin,
                    templatePath.toString(),
                    template.nonAirBlocks(),
                    structureId,
                    displayName,
                    cost,
                    maxHitpoints,
                    totalHammers,
                    hammersPerHour,
                    productionHammersPerHour,
                    beakersPerHour,
                    moneyPerHour,
                    happinessDelta,
                    startedAt,
                    finishAt
            );
            structureRecorded = true;
            storage.recordStructureChunks(buildId, town == null ? null : town.id(), chunks);
            final long finalBuildId = buildId;
            int queuedBlocks = buildQueue.pasteChunked(finalBuildId, origin, template, settings.legacyIncludeAir(), durationMillis, showPreview, true, (placements, backups) -> persistProtectedBlocksAsync(finalBuildId, placements, backups));
            long townId = town == null ? 0L : town.id();
            return new QueuedLegacyBuild(buildId, templatePath, queuedBlocks, template.sizeX(), template.sizeY(), template.sizeZ(), townId, totalHammers, hammersPerHour, durationMillis);
        } catch (SQLException | RuntimeException exception) {
            if (structureRecorded) {
                try {
                    storage.deleteStructureBuild(buildId);
                } catch (SQLException cleanupException) {
                    plugin.getLogger().warning("Unable to rollback failed structure build " + buildId + ": " + cleanupException.getMessage());
                }
            }
            if (cost > 0.0) {
                if (town != null) {
                    storage.depositTown(town.id(), cost);
                } else {
                    economy.deposit(player, cost, "structure build refund " + (structureId == null ? templateName : structureId));
                }
            }
            throw exception;
        }
    }

    private QueuedLegacyBuild startSchematicBuild(Player player, TownRecord town, String type, String templateName, String direction, Path templatePath, WorldEditSchematicTemplate template, String structureId, String displayName, double cost, int maxHitpoints, double totalHammers, double productionHammersPerHour, double beakersPerHour, double moneyPerHour, int happinessDelta, Location origin, boolean showPreview) throws SQLException {
        validateStructureHeight(origin, template.sizeY());
        if (town != null && town.hammersPerHour() <= 0.0) {
            throw new IllegalArgumentException("У города нет производства молотков.");
        }
        if (town != null && storage.hasActiveStructureBuild(town.id())) {
            throw new IllegalArgumentException("В городе уже строится здание. Одновременно можно строить только одно здание.");
        }
        List<StructureChunkCoord> chunks = BuildQueue.chunksFor(origin.getWorld().getName(), origin.getBlockX(), origin.getBlockZ(), template.sizeX(), template.sizeZ());
        validateChunkSolidSupport(origin, template.sizeY(), chunks);
        if (storage.hasStructureChunkOverlap(chunks)) {
            throw new IllegalArgumentException("Нельзя поставить постройку: один из чанков уже занят другой постройкой.");
        }

        double hammersPerHour = town == null ? 0.0 : Math.max(0.0, town.hammersPerHour());
        long startedAt = System.currentTimeMillis();
        long durationMillis = buildDurationMillis(totalHammers, hammersPerHour);
        long finishAt = durationMillis <= 0L ? startedAt : startedAt + durationMillis;

        if (cost > 0.0) {
            if (town != null) {
                storage.withdrawTown(town.id(), cost);
            } else {
                economy.withdraw(player, cost, "structure build " + (structureId == null ? templateName : structureId));
            }
        }
        long buildId = 0L;
        boolean structureRecorded = false;
        try {
            buildId = storage.recordStructureBuild(
                    player.getUniqueId(),
                    town == null ? null : town.id(),
                    type,
                    templateName,
                    direction,
                    origin,
                    templatePath.toString(),
                    template.nonAirBlocks(),
                    structureId,
                    displayName,
                    cost,
                    maxHitpoints,
                    totalHammers,
                    hammersPerHour,
                    productionHammersPerHour,
                    beakersPerHour,
                    moneyPerHour,
                    happinessDelta,
                    startedAt,
                    finishAt
            );
            structureRecorded = true;
            storage.recordStructureChunks(buildId, town == null ? null : town.id(), chunks);
            final long finalBuildId = buildId;
            int queuedBlocks = buildQueue.pasteSchematic(finalBuildId, origin, template, settings.legacyIncludeAir(), durationMillis, showPreview, true, (placements, backups) -> persistProtectedBlocksAsync(finalBuildId, placements, backups));
            long townId = town == null ? 0L : town.id();
            return new QueuedLegacyBuild(buildId, templatePath, queuedBlocks, template.sizeX(), template.sizeY(), template.sizeZ(), townId, totalHammers, hammersPerHour, durationMillis);
        } catch (SQLException | RuntimeException exception) {
            if (structureRecorded) {
                try {
                    storage.deleteStructureBuild(buildId);
                } catch (SQLException cleanupException) {
                    plugin.getLogger().warning("Unable to rollback failed schematic build " + buildId + ": " + cleanupException.getMessage());
                }
            }
            if (cost > 0.0) {
                if (town != null) {
                    storage.depositTown(town.id(), cost);
                } else {
                    economy.deposit(player, cost, "structure build refund " + (structureId == null ? templateName : structureId));
                }
            }
            throw exception;
        }
    }

    public Optional<StorageBootstrap.StructureBuildView> activeBuildInfo(Player player) throws SQLException {
        ResidentProfile resident = game.resident(player);
        if (resident.townId() == null) {
            throw new IllegalArgumentException("Сначала создайте город или вступите в него.");
        }
        return storage.findActiveStructureBuildForTown(resident.townId());
    }

    public Optional<StorageBootstrap.StructureBuildView> structureInfoAt(Location location) throws SQLException {
        return storage.findStructureBuildAtChunk(location.getWorld().getName(), location.getChunk().getX(), location.getChunk().getZ());
    }

    public CancelBuildResult cancelActiveBuild(Player player) throws SQLException {
        TownRecord town = requireMayorTown(player);
        StorageBootstrap.StructureBuildView build = storage.findActiveStructureBuildForTown(town.id())
                .orElseThrow(() -> new IllegalArgumentException("В городе нет активной стройки."));
        long refund = Math.max(0L, Math.round(Math.floor(build.cost() * 0.90)));
        buildQueue.cancelBuild(build.id());
        restoreBlockBackups(storage.loadStructureBlockBackups(build.id()));
        storage.cancelStructureBuild(build.id());
        if (refund > 0L) {
            storage.depositTown(town.id(), refund);
        }
        return new CancelBuildResult(build, refund);
    }

    public DemolishBuildResult demolishStructureAt(Player player) throws SQLException {
        TownRecord town = requireMayorTown(player);
        StorageBootstrap.StructureBuildView build = storage.findStructureBuildAtChunk(player.getWorld().getName(), player.getLocation().getChunk().getX(), player.getLocation().getChunk().getZ())
                .orElseThrow(() -> new IllegalArgumentException("В этом чанке нет постройки."));
        if (build.townId() == null || build.townId() != town.id()) {
            throw new IllegalArgumentException("Эта постройка не принадлежит вашему городу.");
        }
        if (!build.status().equalsIgnoreCase("COMPLETE")) {
            throw new IllegalArgumentException("Разрушать можно только полностью построенные здания. Для активной стройки используйте /b cancel.");
        }
        if (storage.isFirstCompletedStructureInTown(build.id(), town.id())) {
            throw new IllegalArgumentException("Первое здание города нельзя разрушить.");
        }
        long refund = Math.max(0L, Math.round(Math.floor(build.cost() * 0.50)));
        restoreBlockBackups(storage.loadStructureBlockBackups(build.id()));
        storage.demolishStructureBuild(build.id());
        if (refund > 0L) {
            storage.depositTown(town.id(), refund);
        }
        return new DemolishBuildResult(build, refund);
    }

    private void restoreBlockBackups(List<BlockBackup> backups) {
        for (BlockBackup backup : backups) {
            World world = plugin.getServer().getWorld(backup.world());
            if (world == null) {
                continue;
            }
            try {
                world.getBlockAt(backup.x(), backup.y(), backup.z()).setBlockData(Bukkit.createBlockData(backup.blockData()), false);
            } catch (IllegalArgumentException ignored) {
                world.getBlockAt(backup.x(), backup.y(), backup.z()).setType(Material.AIR, false);
            }
        }
    }

    private void sendPhantomPreview(Player player, PendingStructurePreview preview, LegacyDefTemplate template) {
        List<PhantomBlock> blocks = new ArrayList<>();
        World world = preview.origin.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Мир для превью не найден.");
        }

        for (LegacyBlock legacyBlock : template.blocks()) {
            if (legacyBlock.isAir()) {
                continue;
            }
            Location location = new Location(
                    world,
                    preview.origin.getBlockX() + legacyBlock.x(),
                    preview.origin.getBlockY() + legacyBlock.y(),
                    preview.origin.getBlockZ() + legacyBlock.z()
            );
            blocks.add(new PhantomBlock(location, LegacyBlockDataFactory.create(legacyBlock)));
            preview.locations.add(location);
        }

        final int batchSize = 4096;
        new BukkitRunnable() {
            private int cursor;

            @Override
            public void run() {
                if (!player.isOnline() || pendingPreviews.get(player.getUniqueId()) != preview) {
                    cancel();
                    return;
                }
                int sent = 0;
                while (sent < batchSize && cursor < blocks.size()) {
                    PhantomBlock block = blocks.get(cursor++);
                    player.sendBlockChange(block.location, block.blockData);
                    sent++;
                }
                if (cursor >= blocks.size()) {
                    cancel();
                    player.sendMessage(lang().component("build.preview-shown", "&eПревью постройки показано только вам. Напишите yes, чтобы начать строительство, или no, чтобы отменить."));
                    player.sendMessage(lang().component("build.preview-size", "&7Размер: {size_x}x{size_y}x{size_z}, origin: {x} {y} {z}",
                            "size_x", preview.sizeX, "size_y", preview.sizeY, "size_z", preview.sizeZ,
                            "x", preview.origin.getBlockX(), "y", preview.origin.getBlockY(), "z", preview.origin.getBlockZ()));
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void sendPhantomPreview(Player player, PendingStructurePreview preview, WorldEditSchematicTemplate template) {
        List<PhantomBlock> blocks = new ArrayList<>();
        World world = preview.origin.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Мир для превью не найден.");
        }

        for (WorldEditSchematicTemplate.SchematicBlock schematicBlock : template.blocks()) {
            if (schematicBlock.air()) {
                continue;
            }
            Location location = new Location(
                    world,
                    preview.origin.getBlockX() + schematicBlock.x(),
                    preview.origin.getBlockY() + schematicBlock.y(),
                    preview.origin.getBlockZ() + schematicBlock.z()
            );
            blocks.add(new PhantomBlock(location, schematicBlock.blockData()));
            preview.locations.add(location);
        }

        final int batchSize = 4096;
        new BukkitRunnable() {
            private int cursor;

            @Override
            public void run() {
                if (!player.isOnline() || pendingPreviews.get(player.getUniqueId()) != preview) {
                    cancel();
                    return;
                }
                int sent = 0;
                while (sent < batchSize && cursor < blocks.size()) {
                    PhantomBlock block = blocks.get(cursor++);
                    player.sendBlockChange(block.location, block.blockData);
                    sent++;
                }
                if (cursor >= blocks.size()) {
                    cancel();
                    player.sendMessage(lang().component("build.fawe-preview-shown", "&eFAWE-превью постройки показано только вам. Напишите yes, чтобы начать строительство, или no, чтобы отменить."));
                    player.sendMessage(lang().component("build.preview-size", "&7Размер: {size_x}x{size_y}x{size_z}, origin: {x} {y} {z}",
                            "size_x", preview.sizeX, "size_y", preview.sizeY, "size_z", preview.sizeZ,
                            "x", preview.origin.getBlockX(), "y", preview.origin.getBlockY(), "z", preview.origin.getBlockZ()));
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void restorePhantomPreview(Player player, PendingStructurePreview preview) {
        if (!player.isOnline()) {
            return;
        }
        final int batchSize = 8192;
        List<Location> locations = List.copyOf(preview.locations);
        new BukkitRunnable() {
            private int cursor;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }
                int restored = 0;
                while (restored < batchSize && cursor < locations.size()) {
                    Location location = locations.get(cursor++);
                    player.sendBlockChange(location, location.getBlock().getBlockData());
                    restored++;
                }
                if (cursor >= locations.size()) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private LegacyDefTemplate loadTemplate(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        LegacyDefTemplate cached = templateCache.get(normalized);
        if (cached != null) {
            return cached;
        }
        LegacyDefTemplate loaded = loader.load(normalized);
        templateCache.put(normalized, loaded);
        return loaded;
    }

    private WorldEditSchematicTemplate loadSchematic(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        WorldEditSchematicTemplate cached = schematicCache.get(normalized);
        if (cached != null) {
            return cached;
        }
        WorldEditSchematicTemplate loaded = WorldEditSchematicTemplate.load(normalized);
        schematicCache.put(normalized, loaded);
        return loaded;
    }

    private ResolvedBuildTemplate resolveBuildTemplate(StructureDefinition definition, String direction) throws IOException {
        Path schematicPath = resolveSchematic(definition, direction);
        if (schematicPath == null) {
            throw new IOException("FAWE .schem файл не найден для постройки: " + definition.id() + " / " + definition.template());
        }
        return ResolvedBuildTemplate.schematic(schematicPath, loadSchematic(schematicPath));
    }

    private Path resolveSchematic(StructureDefinition definition, String direction) {
        if (!settings.faweSchematicsEnabled()) {
            return null;
        }
        Path root = schematicRoot();
        List<String> bases = List.of(definition.template(), definition.id());
        List<String> extensions = List.of(".schem", ".schematic");
        for (String base : bases) {
            String cleanBase = normalize(base);
            for (String extension : extensions) {
                for (String directionCandidate : directionCandidates(direction)) {
                    Path directed = root.resolve(cleanBase + "_" + directionCandidate + extension);
                    if (Files.isRegularFile(directed)) {
                        return directed;
                    }
                }
                Path plain = root.resolve(cleanBase + extension);
                if (Files.isRegularFile(plain)) {
                    return plain;
                }
            }
        }
        return null;
    }

    public void warmupTemplateCacheAsync() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Path root = templateRoot();
                if (Files.isDirectory(root)) {
                    try (var stream = Files.walk(root)) {
                        stream.filter(Files::isRegularFile)
                                .filter(path -> path.getFileName().toString().endsWith(".def"))
                                .forEach(path -> {
                                    try {
                                        loadTemplate(path);
                                    } catch (IOException exception) {
                                        plugin.getLogger().warning("Unable to warmup CivCraft template " + path + ": " + exception.getMessage());
                                    }
                                });
                    }
                }
                if (settings.faweSchematicsEnabled()) {
                    Path schematicRoot = schematicRoot();
                    if (Files.isDirectory(schematicRoot)) {
                        try (var stream = Files.walk(schematicRoot)) {
                            stream.filter(Files::isRegularFile)
                                    .filter(path -> path.getFileName().toString().endsWith(".schem") || path.getFileName().toString().endsWith(".schematic"))
                                    .forEach(path -> {
                                        try {
                                            loadSchematic(path);
                                        } catch (IOException exception) {
                                            plugin.getLogger().warning("Unable to warmup FAWE schematic " + path + ": " + exception.getMessage());
                                        }
                                    });
                        }
                    }
                }
            } catch (IOException exception) {
                plugin.getLogger().warning("Unable to warmup CivCraft templates: " + exception.getMessage());
            }
        });
    }

    private void validateChunkSolidSupport(Location origin, LegacyDefTemplate template, List<StructureChunkCoord> chunks) {
        validateChunkSolidSupport(origin, template.sizeY(), chunks);
    }

    private void validateChunkSolidSupport(Location origin, int sizeY, List<StructureChunkCoord> chunks) {
        World world = origin.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Мир для постройки не найден.");
        }
        if (sizeY <= 0 || chunks.isEmpty()) {
            throw new IllegalArgumentException("Некорректный шаблон постройки.");
        }

        int baseY = origin.getBlockY();
        int checkHeight = Math.max(1, sizeY);
        int minY = Math.max(world.getMinHeight(), baseY - checkHeight);
        int maxY = Math.min(world.getMaxHeight() - 1, baseY - 1);
        if (maxY < minY) {
            throw new IllegalArgumentException("Под постройкой нет слоя для проверки основы.");
        }

        for (StructureChunkCoord chunk : chunks) {
            int minX = chunk.chunkX() << 4;
            int minZ = chunk.chunkZ() << 4;
            int total = 16 * 16 * (maxY - minY + 1);
            double requiredRatio = Math.max(0.0, Math.min(1.0, settings.minStructureSolidSupportRatio()));
            int requiredSolid = (int) Math.ceil(total * requiredRatio);
            int solid = 0;
            int checked = 0;

            for (int y = maxY; y >= minY; y--) {
                for (int x = minX; x <= minX + 15; x++) {
                    for (int z = minZ; z <= minZ + 15; z++) {
                        checked++;
                        Material type = world.getBlockAt(x, y, z).getType();
                        if (isSolidFoundationBlock(type)) {
                            solid++;
                            if (solid >= requiredSolid) {
                                x = minX + 16;
                                y = minY - 1;
                                break;
                            }
                        }
                        int remaining = total - checked;
                        if (solid + remaining < requiredSolid) {
                            int percent = (int) Math.floor((solid / (double) total) * 100.0);
                            throw new IllegalArgumentException("Недостаточно твердой основы под постройкой в чанке " + chunk.chunkX() + "," + chunk.chunkZ() + ". Сейчас " + percent + "%, нужно минимум " + Math.round(settings.minStructureSolidSupportRatio() * 100.0) + "% твердых блоков ниже высоты постройки.");
                        }
                    }
                }
            }
        }
    }

    private boolean isSolidFoundationBlock(Material type) {
        return type.isSolid() && !type.isAir() && type != Material.WATER && type != Material.LAVA;
    }

    private StructureDefinition definition(String structureId) {
        return definitions.find(structureId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown structure id: " + structureId));
    }

    private TownRecord requireMayorTown(Player player) throws SQLException {
        ResidentProfile resident = game.resident(player);
        TownRecord town = game.town(resident).orElseThrow(() -> new IllegalArgumentException("Сначала создайте город."));
        if (!town.mayorUuid().equals(player.getUniqueId())) {
            throw new IllegalArgumentException("Только мэр города может ставить постройки.");
        }
        if (town.hammersPerHour() <= 0.0) {
            throw new IllegalArgumentException("У города нет производства молотков.");
        }
        return town;
    }

    private void validateStructureHeight(Location origin, int sizeY) {
        int baseY = origin.getBlockY();
        if (baseY < settings.structureMinY() || baseY > settings.structureMaxY()) {
            throw new IllegalArgumentException("Постройку можно ставить только если нижний блок находится в диапазоне высоты " + settings.structureMinY() + "-" + settings.structureMaxY() + ". Сейчас Y=" + baseY + ".");
        }
    }

    private long buildDurationMillis(double totalHammers, double hammersPerHour) {
        if (totalHammers <= 0.0) {
            return 0L;
        }
        if (hammersPerHour <= 0.0) {
            throw new IllegalArgumentException("У города нет производства молотков.");
        }
        double hours = totalHammers / hammersPerHour;
        return Math.max(1L, (long) Math.ceil(hours * 60.0 * 60.0 * 1000.0));
    }

    private void persistProtectedBlocksAsync(long buildId, List<BlockPlacement> placements, List<BlockBackup> backups) {
        List<BlockPlacement> placementCopy = List.copyOf(placements);
        List<BlockBackup> backupCopy = List.copyOf(backups);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                storage.recordProtectedBlocksBackupsAndCompleteBuild(buildId, placementCopy, backupCopy);
            } catch (SQLException exception) {
                plugin.getLogger().warning("Unable to complete structure build " + buildId + ": " + exception.getMessage());
            }
        });
    }

    private Location nextChunkOrigin(Player player, Location location, int sizeX, int sizeZ) {
        int playerChunkX = Math.floorDiv(location.getBlockX(), 16);
        int playerChunkZ = Math.floorDiv(location.getBlockZ(), 16);
        int chunksX = Math.max(1, (int) Math.ceil(Math.max(1, sizeX) / 16.0));
        int chunksZ = Math.max(1, (int) Math.ceil(Math.max(1, sizeZ) / 16.0));
        int startChunkX;
        int startChunkZ;

        double yaw = player.getLocation().getYaw() % 360.0;
        if (yaw < 0.0) {
            yaw += 360.0;
        }

        if (yaw >= 315.0 || yaw < 45.0) {
            // South: the whole structure starts in chunks in front of the player.
            startChunkX = centeredStartChunk(playerChunkX, chunksX);
            startChunkZ = playerChunkZ + 1;
        } else if (yaw < 135.0) {
            // West.
            startChunkX = playerChunkX - chunksX;
            startChunkZ = centeredStartChunk(playerChunkZ, chunksZ);
        } else if (yaw < 225.0) {
            // North.
            startChunkX = centeredStartChunk(playerChunkX, chunksX);
            startChunkZ = playerChunkZ - chunksZ;
        } else {
            // East.
            startChunkX = playerChunkX + 1;
            startChunkZ = centeredStartChunk(playerChunkZ, chunksZ);
        }

        return new Location(location.getWorld(), startChunkX << 4, location.getBlockY(), startChunkZ << 4);
    }

    private int centeredStartChunk(int playerChunk, int structureChunks) {
        return playerChunk - (structureChunks / 2);
    }

    private Path resolveTemplate(String type, String templateName, String direction) throws IOException {
        Path base = templateRoot().resolve("themes").resolve(settings.legacyDefaultTheme()).resolve(type).resolve(templateName);
        for (String directionCandidate : directionCandidates(direction)) {
            Path directed = base.resolve(templateName + "_" + directionCandidate + ".def");
            if (Files.isRegularFile(directed)) {
                return directed;
            }
        }
        Path undirected = base.resolve(templateName + ".def");
        if (Files.isRegularFile(undirected)) {
            return undirected;
        }
        try (var stream = Files.walk(templateRoot(), 6)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> matchesDirectedTemplate(path.getFileName().toString(), templateName, direction) || path.getFileName().toString().equalsIgnoreCase(templateName + ".def"))
                    .min(Comparator.comparing(Path::toString))
                    .orElseThrow(() -> new IOException("Legacy template not found: " + templateName + " direction=" + direction));
        }
    }

    private String normalizeRotationDirection(String direction) {
        String value = direction == null || direction.isBlank() ? "0" : direction.trim();
        if (!List.of("0", "90", "180", "270").contains(value)) {
            value = switch (normalize(value)) {
                case "north" -> "0";
                case "east" -> "90";
                case "south" -> "180";
                case "west" -> "270";
                default -> throw new IllegalArgumentException("Поворот должен быть только 0, 90, 180 или 270.");
            };
        }
        return value;
    }

    private List<String> directionCandidates(String direction) {
        String normalized = normalizeRotationDirection(direction);
        String compass = switch (normalized) {
            case "90" -> "east";
            case "180" -> "south";
            case "270" -> "west";
            default -> "north";
        };
        return List.of(normalized, compass);
    }

    private boolean matchesDirectedTemplate(String fileName, String templateName, String direction) {
        for (String candidate : directionCandidates(direction)) {
            if (fileName.equalsIgnoreCase(templateName + "_" + candidate + ".def")) {
                return true;
            }
        }
        return false;
    }

    private Path templateRoot() {
        return Path.of(settings.legacyTemplateRoot()).toAbsolutePath().normalize();
    }

    private Path schematicRoot() {
        return Path.of(settings.faweSchematicsRoot()).toAbsolutePath().normalize();
    }

    private String normalize(String input) {
        String value = input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_/-]", "");
        if (value.contains("..") || value.isBlank()) {
            throw new IllegalArgumentException("Invalid template name: " + input);
        }
        return value;
    }

    private record ResolvedBuildTemplate(Path path, LegacyDefTemplate legacy, WorldEditSchematicTemplate schematic, int sizeX, int sizeY, int sizeZ) {
        private static ResolvedBuildTemplate legacy(Path path, LegacyDefTemplate legacy) {
            return new ResolvedBuildTemplate(path, legacy, null, legacy.sizeX(), legacy.sizeY(), legacy.sizeZ());
        }

        private static ResolvedBuildTemplate schematic(Path path, WorldEditSchematicTemplate schematic) {
            return new ResolvedBuildTemplate(path, null, schematic, schematic.sizeX(), schematic.sizeY(), schematic.sizeZ());
        }
    }

    private static final class PendingStructurePreview {
        private final UUID playerId;
        private final long townId;
        private final String structureId;
        private final String direction;
        private final Location origin;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final boolean foundation;
        private final List<Location> locations = new ArrayList<>();

        private PendingStructurePreview(UUID playerId, long townId, String structureId, String direction, Location origin, int sizeX, int sizeY, int sizeZ, boolean foundation) {
            this.playerId = playerId;
            this.townId = townId;
            this.structureId = structureId;
            this.direction = direction;
            this.origin = origin.clone();
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.foundation = foundation;
        }
    }

    public record CancelBuildResult(StorageBootstrap.StructureBuildView build, long refund) {
    }

    public record DemolishBuildResult(StorageBootstrap.StructureBuildView build, long refund) {
    }

    private Lang lang() {
        if (plugin instanceof CivCraftModernPlugin modern && modern.lang() != null) {
            return modern.lang();
        }
        if (fallbackLang == null) {
            fallbackLang = new Lang(plugin);
        }
        return fallbackLang;
    }

    private record PhantomBlock(Location location, BlockData blockData) {
    }
}
