package com.avrgaming.civcraft.modern.build;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public final class BuildQueue {
    private static final long CHUNKED_INSTANT_BUILD_DURATION = 0L;
    private static final int FAST_CHUNK_LOAD_BUDGET = 64;
    private static final int FAST_CLEAR_MIN_BUDGET = 8192;
    private static final int FAST_PREVIEW_MIN_BUDGET = 4096;

    private final JavaPlugin plugin;
    private final Map<Long, CancellableBuild> activeBuilds = new ConcurrentHashMap<>();
    private int maxBlocksPerTick;

    public BuildQueue(JavaPlugin plugin, int maxBlocksPerTick) {
        this.plugin = plugin;
        this.maxBlocksPerTick = Math.max(1, maxBlocksPerTick);
    }

    public void updateBudget(int maxBlocksPerTick) {
        this.maxBlocksPerTick = Math.max(1, maxBlocksPerTick);
    }

    public boolean cancelBuild(long buildId) {
        CancellableBuild build = activeBuilds.get(buildId);
        if (build == null) {
            return false;
        }
        build.requestCancel();
        return true;
    }

    public int paste(Location origin, LegacyDefTemplate template, boolean includeAir, java.util.function.Consumer<List<BlockPlacement>> onComplete) {
        return pasteChunked(0L, origin, template, includeAir, CHUNKED_INSTANT_BUILD_DURATION, false, true, (placements, backups) -> onComplete.accept(placements));
    }

    public int pasteChunked(Location origin, LegacyDefTemplate template, boolean includeAir, long buildDurationMillis, boolean showBedrockPreview, java.util.function.Consumer<List<BlockPlacement>> onComplete) {
        return pasteChunked(0L, origin, template, includeAir, buildDurationMillis, showBedrockPreview, true, (placements, backups) -> onComplete.accept(placements));
    }

    public int pasteChunked(Location origin, LegacyDefTemplate template, boolean includeAir, long buildDurationMillis, boolean showBedrockPreview, boolean clearChunks, java.util.function.Consumer<List<BlockPlacement>> onComplete) {
        return pasteChunked(0L, origin, template, includeAir, buildDurationMillis, showBedrockPreview, clearChunks, (placements, backups) -> onComplete.accept(placements));
    }

    public int pasteChunked(long buildId, Location origin, LegacyDefTemplate template, boolean includeAir, long buildDurationMillis, boolean showBedrockPreview, boolean clearChunks, BuildCompleteHandler onComplete) {
        int queuedBlocks = includeAir ? template.blocks().size() : template.nonAirBlocks();
        int originX = origin.getBlockX();
        int originY = origin.getBlockY();
        int originZ = origin.getBlockZ();
        String worldName = origin.getWorld().getName();
        long duration = Math.max(0L, buildDurationMillis);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            PreparedBuild prepared = PreparedBuild.prepare(worldName, originX, originY, originZ, template, includeAir, showBedrockPreview, clearChunks);
            plugin.getServer().getScheduler().runTask(plugin, () -> new BuildSession(buildId, origin, prepared, duration, showBedrockPreview, onComplete).start());
        });
        return queuedBlocks;
    }

    public int pasteSchematic(Location origin, WorldEditSchematicTemplate template, boolean includeAir, long buildDurationMillis, boolean showBedrockPreview, boolean clearChunks, java.util.function.Consumer<List<BlockPlacement>> onComplete) {
        return pasteSchematic(0L, origin, template, includeAir, buildDurationMillis, showBedrockPreview, clearChunks, (placements, backups) -> onComplete.accept(placements));
    }

    public int pasteSchematic(long buildId, Location origin, WorldEditSchematicTemplate template, boolean includeAir, long buildDurationMillis, boolean showBedrockPreview, boolean clearChunks, BuildCompleteHandler onComplete) {
        int queuedBlocks = includeAir ? template.blocks().size() : template.nonAirBlocks();
        int originX = origin.getBlockX();
        int originY = origin.getBlockY();
        int originZ = origin.getBlockZ();
        String worldName = origin.getWorld().getName();
        long duration = Math.max(0L, buildDurationMillis);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            PreparedBuild prepared = PreparedBuild.prepareSchematic(worldName, originX, originY, originZ, template, includeAir, showBedrockPreview, clearChunks);
            plugin.getServer().getScheduler().runTask(plugin, () -> new SchematicBuildSession(buildId, origin, prepared, template, includeAir, duration, showBedrockPreview, onComplete).start());
        });
        return queuedBlocks;
    }

    @FunctionalInterface
    public interface BuildCompleteHandler {
        void accept(List<BlockPlacement> placements, List<BlockBackup> backups);
    }

    private interface CancellableBuild {
        void requestCancel();
    }

    private final class BuildSession implements CancellableBuild {
        private final long buildId;
        private final Location origin;
        private final PreparedBuild prepared;
        private final long buildDurationMillis;
        private final boolean showBedrockPreview;
        private final BuildCompleteHandler onComplete;
        private final List<BlockPlacement> placements;
        private final Map<String, BlockBackup> backupsByKey = new LinkedHashMap<>();
        private final List<ClearBlock> placedStructureBlocks = new ArrayList<>();
        private final Map<String, PreviewBlock> activePreviewByKey = new LinkedHashMap<>();
        private final Set<String> placedKeys = new HashSet<>();
        private int chunkLoadCursor;
        private int clearCursor;
        private int previewPlaceCursor;
        private int previewRemoveCursor;
        private int placedBlockCursor;
        private long startedAtMillis;
        private boolean startedBuilding;
        private volatile boolean cancelRequested;

        private BuildSession(long buildId, Location origin, PreparedBuild prepared, long buildDurationMillis, boolean showBedrockPreview, BuildCompleteHandler onComplete) {
            this.buildId = buildId;
            this.origin = origin;
            this.prepared = prepared;
            this.buildDurationMillis = buildDurationMillis;
            this.showBedrockPreview = showBedrockPreview;
            this.onComplete = onComplete;
            this.placements = new ArrayList<>(prepared.orderedBlocks.size());
        }

        private void start() {
            if (buildId > 0L) {
                activeBuilds.put(buildId, this);
            }
            this.startedAtMillis = System.currentTimeMillis();
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (cancelRequested) {
                        cleanupCanceledBuild();
                        if (buildId > 0L) {
                            activeBuilds.remove(buildId);
                        }
                        cancel();
                        return;
                    }
                    int tickBudget = buildDurationMillis <= 0L ? Integer.MAX_VALUE / 4 : Math.max(1, maxBlocksPerTick);
                    tickBudget = loadChunksFast(tickBudget);
                    if (chunkLoadCursor < prepared.chunks.size()) {
                        return;
                    }
                    tickBudget = clearExtraBlocksFast(tickBudget);
                    if (clearCursor < prepared.clearBlocks.size()) {
                        return;
                    }
                    tickBudget = placeBedrockPreviewFast(tickBudget);
                    if (!startedBuilding) {
                        startedBuilding = true;
                        startedAtMillis = System.currentTimeMillis();
                    }
                    tickBudget = placeDueStructureBlocks(tickBudget);
                    removeBedrockByProgress(tickBudget);

                    if (placedBlockCursor >= prepared.orderedBlocks.size()) {
                        restoreAllBedrockPreview();
                        if (buildId > 0L) {
                            activeBuilds.remove(buildId);
                        }
                        cancel();
                        onComplete.accept(List.copyOf(placements), List.copyOf(backupsByKey.values()));
                    }
                }
            }.runTaskTimer(plugin, 1L, 1L);
        }

        private int loadChunksFast(int budget) {
            int used = 0;
            World world = origin.getWorld();
            int chunkBudget = Math.max(FAST_CHUNK_LOAD_BUDGET, budget);
            while (used < chunkBudget && chunkLoadCursor < prepared.chunks.size()) {
                StructureChunkCoord chunk = prepared.chunks.get(chunkLoadCursor++);
                world.getChunkAt(chunk.chunkX(), chunk.chunkZ()).load(true);
                used++;
            }
            return Math.max(0, budget - Math.min(used, budget));
        }

        private int clearExtraBlocksFast(int budget) {
            if (prepared.clearBlocks.isEmpty()) {
                return budget;
            }
            int used = 0;
            World world = origin.getWorld();
            int clearBudget = buildDurationMillis <= 0L ? Integer.MAX_VALUE / 4 : Math.max(FAST_CLEAR_MIN_BUDGET, Math.max(1, maxBlocksPerTick) * 64);
            while (used < clearBudget && clearCursor < prepared.clearBlocks.size()) {
                ClearBlock clear = prepared.clearBlocks.get(clearCursor++);
                Block block = world.getBlockAt(clear.x, clear.y, clear.z);
                rememberBackup(world.getName(), clear.x, clear.y, clear.z, block);
                if (!block.getType().isAir()) {
                    block.setType(Material.AIR, false);
                }
                used++;
            }
            return Math.max(0, budget - Math.min(used, budget));
        }

        private int placeBedrockPreviewFast(int budget) {
            if (!showBedrockPreview || prepared.previewBlocks.isEmpty()) {
                return budget;
            }
            int used = 0;
            int previewBudget = buildDurationMillis <= 0L ? Integer.MAX_VALUE / 4 : Math.max(FAST_PREVIEW_MIN_BUDGET, Math.max(1, maxBlocksPerTick) * 32);
            while (used < previewBudget && previewPlaceCursor < prepared.previewBlocks.size()) {
                PreviewBlock preview = prepared.previewBlocks.get(previewPlaceCursor++);
                if (preview.removed || preview.active || placedKeys.contains(preview.key())) {
                    continue;
                }
                Block block = origin.getWorld().getBlockAt(preview.x, preview.y, preview.z);
                preview.originalData = block.getBlockData().clone();
                block.setType(Material.BEDROCK, false);
                preview.active = true;
                activePreviewByKey.put(preview.key(), preview);
                used++;
            }
            return Math.max(0, budget - Math.min(used, budget));
        }

        private int placeDueStructureBlocks(int budget) {
            if (budget <= 0) {
                return budget;
            }
            int target = targetPlacedBlocks();
            int used = 0;
            while (used < budget && placedBlockCursor < target && placedBlockCursor < prepared.orderedBlocks.size()) {
                LegacyBlock legacyBlock = prepared.orderedBlocks.get(placedBlockCursor++);
                String key = blockKey(prepared.world, prepared.originX + legacyBlock.x(), prepared.originY + legacyBlock.y(), prepared.originZ + legacyBlock.z());
                PreviewBlock preview = activePreviewByKey.remove(key);
                Block existingBlock = origin.getWorld().getBlockAt(prepared.originX + legacyBlock.x(), prepared.originY + legacyBlock.y(), prepared.originZ + legacyBlock.z());
                if (preview != null && preview.originalData != null) {
                    rememberBackupData(prepared.world, prepared.originX + legacyBlock.x(), prepared.originY + legacyBlock.y(), prepared.originZ + legacyBlock.z(), preview.originalData);
                    preview.active = false;
                    preview.removed = true;
                } else {
                    rememberBackup(prepared.world, prepared.originX + legacyBlock.x(), prepared.originY + legacyBlock.y(), prepared.originZ + legacyBlock.z(), existingBlock);
                }
                BlockPlacement placement = placeStructureBlock(origin, legacyBlock);
                placedKeys.add(key);
                if (placement != null) {
                    placedStructureBlocks.add(new ClearBlock(placement.x(), placement.y(), placement.z()));
                }
                if (placement != null) {
                    placements.add(placement);
                }
                used++;
            }
            return budget - used;
        }

        private void removeBedrockByProgress(int budget) {
            if (!showBedrockPreview || budget <= 0 || prepared.previewBlocks.isEmpty()) {
                return;
            }
            int targetRemove = (int) Math.floor(prepared.previewBlocks.size() * buildProgress());
            int used = 0;
            while (used < budget && previewRemoveCursor < targetRemove && previewRemoveCursor < prepared.previewBlocks.size()) {
                PreviewBlock preview = prepared.previewBlocks.get(previewRemoveCursor++);
                restorePreview(preview);
                used++;
            }
        }

        private void cleanupCanceledBuild() {
            restoreAllBedrockPreview();
            restoreBackups();
            placedStructureBlocks.clear();
            placements.clear();
        }

        private void rememberBackup(String worldName, int x, int y, int z, Block block) {
            rememberBackupData(worldName, x, y, z, block.getBlockData());
        }

        private void rememberBackupData(String worldName, int x, int y, int z, BlockData blockData) {
            String key = blockKey(worldName, x, y, z);
            backupsByKey.computeIfAbsent(key, ignored -> new BlockBackup(worldName, x, y, z, blockData.getAsString()));
        }

        private void restoreBackups() {
            for (BlockBackup backup : backupsByKey.values()) {
                World backupWorld = plugin.getServer().getWorld(backup.world());
                if (backupWorld == null) {
                    continue;
                }
                try {
                    backupWorld.getBlockAt(backup.x(), backup.y(), backup.z()).setBlockData(Bukkit.createBlockData(backup.blockData()), false);
                } catch (IllegalArgumentException ignored) {
                    backupWorld.getBlockAt(backup.x(), backup.y(), backup.z()).setType(Material.AIR, false);
                }
            }
            backupsByKey.clear();
        }

        @Override
        public void requestCancel() {
            this.cancelRequested = true;
        }

        private int targetPlacedBlocks() {
            if (prepared.orderedBlocks.isEmpty()) {
                return 0;
            }
            if (buildDurationMillis <= 0L) {
                return prepared.orderedBlocks.size();
            }
            long elapsed = Math.max(0L, System.currentTimeMillis() - startedAtMillis);
            if (elapsed >= buildDurationMillis) {
                return prepared.orderedBlocks.size();
            }
            double ratio = Math.min(1.0, elapsed / (double) buildDurationMillis);
            return Math.max(0, (int) Math.floor(prepared.orderedBlocks.size() * ratio));
        }

        private double buildProgress() {
            if (prepared.orderedBlocks.isEmpty()) {
                return 1.0;
            }
            if (buildDurationMillis > 0L) {
                long elapsed = Math.max(0L, System.currentTimeMillis() - startedAtMillis);
                return Math.min(1.0, elapsed / (double) buildDurationMillis);
            }
            return Math.min(1.0, placedBlockCursor / (double) prepared.orderedBlocks.size());
        }

        private void restoreAllBedrockPreview() {
            for (PreviewBlock preview : prepared.previewBlocks) {
                restorePreview(preview);
            }
            activePreviewByKey.clear();
        }

        private void restorePreview(PreviewBlock preview) {
            if (preview.removed) {
                return;
            }
            preview.removed = true;
            activePreviewByKey.remove(preview.key());
            if (!preview.active || preview.originalData == null) {
                return;
            }
            World world = plugin.getServer().getWorld(preview.world);
            if (world == null) {
                return;
            }
            Block block = world.getBlockAt(preview.x, preview.y, preview.z);
            if (block.getType() == Material.BEDROCK) {
                block.setBlockData(preview.originalData, false);
            }
            preview.active = false;
        }
    }

    private final class SchematicBuildSession implements CancellableBuild {
        private final long buildId;
        private final Location origin;
        private final PreparedBuild prepared;
        private final boolean showBedrockPreview;
        private final BuildCompleteHandler onComplete;
        private final List<WorldEditSchematicTemplate.SchematicBlock> orderedBlocks;
        private final List<BlockPlacement> placements;
        private final Map<String, BlockBackup> backupsByKey = new LinkedHashMap<>();
        private final List<ClearBlock> placedStructureBlocks = new ArrayList<>();
        private final Map<String, PreviewBlock> activePreviewByKey = new LinkedHashMap<>();
        private int chunkLoadCursor;
        private int clearCursor;
        private int previewPlaceCursor;
        private int previewRemoveCursor;
        private int placedBlockCursor;
        private long startedAtMillis;
        private boolean startedBuilding;
        private volatile boolean cancelRequested;

        private SchematicBuildSession(long buildId, Location origin, PreparedBuild prepared, WorldEditSchematicTemplate schematic, boolean includeAir, long buildDurationMillis, boolean showBedrockPreview, BuildCompleteHandler onComplete) {
            this.buildId = buildId;
            this.origin = origin;
            this.prepared = prepared;
            this.buildDurationMillis = buildDurationMillis;
            this.showBedrockPreview = showBedrockPreview;
            this.onComplete = onComplete;
            this.orderedBlocks = schematic.blocks().stream()
                    .filter(block -> includeAir || !block.air())
                    .sorted(Comparator
                            .comparingInt(WorldEditSchematicTemplate.SchematicBlock::y)
                            .thenComparingInt(WorldEditSchematicTemplate.SchematicBlock::z)
                            .thenComparingInt(WorldEditSchematicTemplate.SchematicBlock::x))
                    .toList();
            this.placements = new ArrayList<>(schematic.nonAirBlocks());
        }

        private final long buildDurationMillis;

        private void start() {
            if (buildId > 0L) {
                activeBuilds.put(buildId, this);
            }
            this.startedAtMillis = System.currentTimeMillis();
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (cancelRequested) {
                        cleanupCanceledBuild();
                        if (buildId > 0L) {
                            activeBuilds.remove(buildId);
                        }
                        cancel();
                        return;
                    }
                    int tickBudget = buildDurationMillis <= 0L ? Integer.MAX_VALUE / 4 : Math.max(1, maxBlocksPerTick);
                    tickBudget = loadChunksFast(tickBudget);
                    if (chunkLoadCursor < prepared.chunks.size()) {
                        return;
                    }
                    tickBudget = clearExtraBlocksFast(tickBudget);
                    if (clearCursor < prepared.clearBlocks.size()) {
                        return;
                    }
                    tickBudget = placeBedrockPreviewFast(tickBudget);
                    if (!startedBuilding) {
                        startedBuilding = true;
                        startedAtMillis = System.currentTimeMillis();
                    }

                    tickBudget = placeDueSchematicBlocks(tickBudget);
                    removeBedrockByProgress(tickBudget);

                    if (placedBlockCursor >= orderedBlocks.size()) {
                        restoreAllBedrockPreview();
                        if (buildId > 0L) {
                            activeBuilds.remove(buildId);
                        }
                        cancel();
                        onComplete.accept(List.copyOf(placements), List.copyOf(backupsByKey.values()));
                    }
                }
            }.runTaskTimer(plugin, 1L, 1L);
        }

        private int loadChunksFast(int budget) {
            int used = 0;
            World world = origin.getWorld();
            int chunkBudget = Math.max(FAST_CHUNK_LOAD_BUDGET, budget);
            while (used < chunkBudget && chunkLoadCursor < prepared.chunks.size()) {
                StructureChunkCoord chunk = prepared.chunks.get(chunkLoadCursor++);
                world.getChunkAt(chunk.chunkX(), chunk.chunkZ()).load(true);
                used++;
            }
            return Math.max(0, budget - Math.min(used, budget));
        }

        private int clearExtraBlocksFast(int budget) {
            if (prepared.clearBlocks.isEmpty()) {
                return budget;
            }
            int used = 0;
            World world = origin.getWorld();
            int clearBudget = buildDurationMillis <= 0L ? Integer.MAX_VALUE / 4 : Math.max(FAST_CLEAR_MIN_BUDGET, Math.max(1, maxBlocksPerTick) * 64);
            while (used < clearBudget && clearCursor < prepared.clearBlocks.size()) {
                ClearBlock clear = prepared.clearBlocks.get(clearCursor++);
                Block block = world.getBlockAt(clear.x, clear.y, clear.z);
                rememberBackup(world.getName(), clear.x, clear.y, clear.z, block);
                if (!block.getType().isAir()) {
                    block.setType(Material.AIR, false);
                }
                used++;
            }
            return Math.max(0, budget - Math.min(used, budget));
        }

        private int placeBedrockPreviewFast(int budget) {
            if (!showBedrockPreview || prepared.previewBlocks.isEmpty()) {
                return budget;
            }
            int used = 0;
            int previewBudget = buildDurationMillis <= 0L ? Integer.MAX_VALUE / 4 : Math.max(FAST_PREVIEW_MIN_BUDGET, Math.max(1, maxBlocksPerTick) * 32);
            while (used < previewBudget && previewPlaceCursor < prepared.previewBlocks.size()) {
                PreviewBlock preview = prepared.previewBlocks.get(previewPlaceCursor++);
                if (preview.removed || preview.active) {
                    continue;
                }
                Block block = origin.getWorld().getBlockAt(preview.x, preview.y, preview.z);
                preview.originalData = block.getBlockData().clone();
                block.setType(Material.BEDROCK, false);
                preview.active = true;
                activePreviewByKey.put(preview.key(), preview);
                used++;
            }
            return Math.max(0, budget - Math.min(used, budget));
        }

        private int placeDueSchematicBlocks(int budget) {
            if (budget <= 0) {
                return budget;
            }
            int target = targetPlacedBlocks();
            int used = 0;
            World world = origin.getWorld();
            while (used < budget && placedBlockCursor < target && placedBlockCursor < orderedBlocks.size()) {
                WorldEditSchematicTemplate.SchematicBlock schematicBlock = orderedBlocks.get(placedBlockCursor++);
                int x = origin.getBlockX() + schematicBlock.x();
                int y = origin.getBlockY() + schematicBlock.y();
                int z = origin.getBlockZ() + schematicBlock.z();
                String key = blockKey(world.getName(), x, y, z);
                PreviewBlock preview = activePreviewByKey.remove(key);
                Block block = world.getBlockAt(x, y, z);
                if (preview != null && preview.originalData != null) {
                    rememberBackupData(world.getName(), x, y, z, preview.originalData);
                    preview.active = false;
                    preview.removed = true;
                } else {
                    rememberBackup(world.getName(), x, y, z, block);
                }
                block.setBlockData(schematicBlock.blockData(), false);
                if (!schematicBlock.air()) {
                    placements.add(new BlockPlacement(world.getName(), x, y, z, schematicBlock.materialId()));
                    placedStructureBlocks.add(new ClearBlock(x, y, z));
                }
                used++;
            }
            return budget - used;
        }

        private void cleanupCanceledBuild() {
            restoreAllBedrockPreview();
            restoreBackups();
            placedStructureBlocks.clear();
            placements.clear();
        }

        private void rememberBackup(String worldName, int x, int y, int z, Block block) {
            rememberBackupData(worldName, x, y, z, block.getBlockData());
        }

        private void rememberBackupData(String worldName, int x, int y, int z, BlockData blockData) {
            String key = blockKey(worldName, x, y, z);
            backupsByKey.computeIfAbsent(key, ignored -> new BlockBackup(worldName, x, y, z, blockData.getAsString()));
        }

        private void restoreBackups() {
            for (BlockBackup backup : backupsByKey.values()) {
                World backupWorld = plugin.getServer().getWorld(backup.world());
                if (backupWorld == null) {
                    continue;
                }
                try {
                    backupWorld.getBlockAt(backup.x(), backup.y(), backup.z()).setBlockData(Bukkit.createBlockData(backup.blockData()), false);
                } catch (IllegalArgumentException ignored) {
                    backupWorld.getBlockAt(backup.x(), backup.y(), backup.z()).setType(Material.AIR, false);
                }
            }
            backupsByKey.clear();
        }

        @Override
        public void requestCancel() {
            this.cancelRequested = true;
        }

        private int targetPlacedBlocks() {
            if (orderedBlocks.isEmpty()) {
                return 0;
            }
            if (buildDurationMillis <= 0L) {
                return orderedBlocks.size();
            }
            long elapsed = Math.max(0L, System.currentTimeMillis() - startedAtMillis);
            if (elapsed >= buildDurationMillis) {
                return orderedBlocks.size();
            }
            double ratio = Math.min(1.0, elapsed / (double) buildDurationMillis);
            return Math.max(0, (int) Math.floor(orderedBlocks.size() * ratio));
        }

        private void removeBedrockByProgress(int budget) {
            if (!showBedrockPreview || budget <= 0 || prepared.previewBlocks.isEmpty()) {
                return;
            }
            int targetRemove = (int) Math.floor(prepared.previewBlocks.size() * buildProgress());
            int used = 0;
            while (used < budget && previewRemoveCursor < targetRemove && previewRemoveCursor < prepared.previewBlocks.size()) {
                PreviewBlock preview = prepared.previewBlocks.get(previewRemoveCursor++);
                restorePreview(preview);
                used++;
            }
        }

        private double buildProgress() {
            if (orderedBlocks.isEmpty()) {
                return 1.0;
            }
            if (buildDurationMillis <= 0L) {
                return 1.0;
            }
            long elapsed = Math.max(0L, System.currentTimeMillis() - startedAtMillis);
            return Math.min(1.0, elapsed / (double) buildDurationMillis);
        }

        private void restoreAllBedrockPreview() {
            for (PreviewBlock preview : prepared.previewBlocks) {
                restorePreview(preview);
            }
            activePreviewByKey.clear();
        }

        private void restorePreview(PreviewBlock preview) {
            if (preview.removed) {
                return;
            }
            preview.removed = true;
            activePreviewByKey.remove(preview.key());
            if (!preview.active || preview.originalData == null) {
                return;
            }
            World world = plugin.getServer().getWorld(preview.world);
            if (world == null) {
                return;
            }
            Block block = world.getBlockAt(preview.x, preview.y, preview.z);
            if (block.getType() == Material.BEDROCK) {
                block.setBlockData(preview.originalData, false);
            }
            preview.active = false;
        }
    }

    private static final class PreparedBuild {
        private final String world;
        private final int originX;
        private final int originY;
        private final int originZ;
        private final List<LegacyBlock> orderedBlocks;
        private final List<StructureChunkCoord> chunks;
        private final List<ClearBlock> clearBlocks;
        private final List<PreviewBlock> previewBlocks;

        private PreparedBuild(String world, int originX, int originY, int originZ, List<LegacyBlock> orderedBlocks, List<StructureChunkCoord> chunks, List<ClearBlock> clearBlocks, List<PreviewBlock> previewBlocks) {
            this.world = world;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.orderedBlocks = orderedBlocks;
            this.chunks = chunks;
            this.clearBlocks = clearBlocks;
            this.previewBlocks = previewBlocks;
        }

        private static PreparedBuild prepare(String world, int originX, int originY, int originZ, LegacyDefTemplate template, boolean includeAir, boolean showPreview, boolean clearChunks) {
            List<StructureChunkCoord> chunks = chunksFor(world, originX, originZ, template.sizeX(), template.sizeZ());
            List<LegacyBlock> ordered = orderedBlocksByChunk(originX, originZ, template, includeAir);
            Set<String> structureKeys = structureKeys(world, originX, originY, originZ, template, includeAir);
            List<ClearBlock> clearBlocks = clearChunks ? clearBlocksFor(world, originX, originY, originZ, template.sizeY(), chunks, structureKeys) : List.of();
            List<PreviewBlock> previewBlocks = showPreview ? previewBlocksForChunks(world, originY, template.sizeY(), chunks) : List.of();
            return new PreparedBuild(world, originX, originY, originZ, ordered, chunks, clearBlocks, previewBlocks);
        }

        private static PreparedBuild prepareSchematic(String world, int originX, int originY, int originZ, WorldEditSchematicTemplate template, boolean includeAir, boolean showPreview, boolean clearChunks) {
            List<StructureChunkCoord> chunks = chunksFor(world, originX, originZ, template.sizeX(), template.sizeZ());
            Set<String> structureKeys = template.structureKeys(world, originX, originY, originZ, includeAir);
            List<ClearBlock> clearBlocks = clearChunks ? clearBlocksFor(world, originX, originY, originZ, template.sizeY(), chunks, structureKeys) : List.of();
            List<PreviewBlock> previewBlocks = showPreview ? previewBlocksForChunks(world, originY, template.sizeY(), chunks) : List.of();
            return new PreparedBuild(world, originX, originY, originZ, List.of(), chunks, clearBlocks, previewBlocks);
        }

        private static List<LegacyBlock> orderedBlocksByChunk(int originX, int originZ, LegacyDefTemplate template, boolean includeAir) {
            Map<Long, List<LegacyBlock>> byChunk = new TreeMap<>();
            for (LegacyBlock legacyBlock : template.blocks()) {
                if (!includeAir && legacyBlock.isAir()) {
                    continue;
                }
                int worldX = originX + legacyBlock.x();
                int worldZ = originZ + legacyBlock.z();
                int chunkX = Math.floorDiv(worldX, 16);
                int chunkZ = Math.floorDiv(worldZ, 16);
                long key = chunkKey(chunkX, chunkZ);
                byChunk.computeIfAbsent(key, ignored -> new ArrayList<>()).add(legacyBlock);
            }
            List<LegacyBlock> out = new ArrayList<>();
            for (List<LegacyBlock> chunkBlocks : byChunk.values()) {
                chunkBlocks.sort(Comparator
                        .comparingInt(LegacyBlock::y)
                        .thenComparingInt(LegacyBlock::z)
                        .thenComparingInt(LegacyBlock::x));
                out.addAll(chunkBlocks);
            }
            return List.copyOf(out);
        }

        private static Set<String> structureKeys(String world, int originX, int originY, int originZ, LegacyDefTemplate template, boolean includeAir) {
            Set<String> keys = new HashSet<>();
            for (LegacyBlock legacyBlock : template.blocks()) {
                if (!includeAir && legacyBlock.isAir()) {
                    continue;
                }
                keys.add(blockKey(world, originX + legacyBlock.x(), originY + legacyBlock.y(), originZ + legacyBlock.z()));
            }
            return keys;
        }

        private static List<ClearBlock> clearBlocksFor(String world, int originX, int originY, int originZ, int sizeY, List<StructureChunkCoord> chunks, Set<String> structureKeys) {
            List<ClearBlock> out = new ArrayList<>();
            int topY = originY + Math.max(1, sizeY) - 1;
            for (StructureChunkCoord chunk : chunks) {
                int minX = chunk.chunkX() << 4;
                int minZ = chunk.chunkZ() << 4;
                for (int y = originY; y <= topY; y++) {
                    for (int x = minX; x <= minX + 15; x++) {
                        for (int z = minZ; z <= minZ + 15; z++) {
                            if (!structureKeys.contains(blockKey(world, x, y, z))) {
                                out.add(new ClearBlock(x, y, z));
                            }
                        }
                    }
                }
            }
            return List.copyOf(out);
        }

        private static List<PreviewBlock> previewBlocksForChunks(String world, int originY, int sizeY, List<StructureChunkCoord> chunks) {
            if (chunks.isEmpty()) {
                return List.of();
            }

            int bottomY = originY;
            int topY = originY + Math.max(1, sizeY) - 1;
            int minChunkX = Integer.MAX_VALUE;
            int maxChunkX = Integer.MIN_VALUE;
            int minChunkZ = Integer.MAX_VALUE;
            int maxChunkZ = Integer.MIN_VALUE;

            for (StructureChunkCoord chunk : chunks) {
                minChunkX = Math.min(minChunkX, chunk.chunkX());
                maxChunkX = Math.max(maxChunkX, chunk.chunkX());
                minChunkZ = Math.min(minChunkZ, chunk.chunkZ());
                maxChunkZ = Math.max(maxChunkZ, chunk.chunkZ());
            }

            int minX = minChunkX << 4;
            int maxX = (maxChunkX << 4) + 15;
            int minZ = minChunkZ << 4;
            int maxZ = (maxChunkZ << 4) + 15;
            Map<String, PreviewBlock> unique = new LinkedHashMap<>();

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    addPreview(unique, world, x, bottomY, z);
                }
            }

            for (int x = minX; x <= maxX; x++) {
                addPreview(unique, world, x, topY, minZ);
                addPreview(unique, world, x, topY, maxZ);
            }
            for (int z = minZ; z <= maxZ; z++) {
                addPreview(unique, world, minX, topY, z);
                addPreview(unique, world, maxX, topY, z);
            }

            for (int y = bottomY; y <= topY; y++) {
                addPreview(unique, world, minX, y, minZ);
                addPreview(unique, world, minX, y, maxZ);
                addPreview(unique, world, maxX, y, minZ);
                addPreview(unique, world, maxX, y, maxZ);
            }

            return List.copyOf(unique.values());
        }

        private static void addPreview(Map<String, PreviewBlock> unique, String world, int x, int y, int z) {
            String key = blockKey(world, x, y, z);
            unique.computeIfAbsent(key, ignored -> new PreviewBlock(world, x, y, z));
        }
    }

    public static List<StructureChunkCoord> chunksFor(Location origin, LegacyDefTemplate template) {
        return chunksFor(origin.getWorld().getName(), origin.getBlockX(), origin.getBlockZ(), template.sizeX(), template.sizeZ());
    }

    public static List<StructureChunkCoord> chunksFor(String world, int originX, int originZ, int sizeX, int sizeZ) {
        int minChunkX = Math.floorDiv(originX, 16);
        int maxChunkX = Math.floorDiv(originX + Math.max(1, sizeX) - 1, 16);
        int minChunkZ = Math.floorDiv(originZ, 16);
        int maxChunkZ = Math.floorDiv(originZ + Math.max(1, sizeZ) - 1, 16);
        List<StructureChunkCoord> chunks = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                chunks.add(new StructureChunkCoord(world, chunkX, chunkZ));
            }
        }
        return List.copyOf(chunks);
    }

    private BlockPlacement placeStructureBlock(Location origin, LegacyBlock legacyBlock) {
        Material material = LegacyMaterialMapper.map(legacyBlock.legacyId(), legacyBlock.legacyData());
        Block block = origin.getWorld().getBlockAt(
                origin.getBlockX() + legacyBlock.x(),
                origin.getBlockY() + legacyBlock.y(),
                origin.getBlockZ() + legacyBlock.z()
        );
        block.setType(material, false);
        LegacyBlockStateApplier.apply(block, legacyBlock);

        if (!legacyBlock.signLines().isEmpty() && block.getState() instanceof Sign sign) {
            for (int i = 0; i < Math.min(4, legacyBlock.signLines().size()); i++) {
                sign.line(i, net.kyori.adventure.text.Component.text(legacyBlock.signLines().get(i)));
            }
            sign.update(true, false);
        }
        if (material.isAir()) {
            return null;
        }
        return new BlockPlacement(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), material.name());
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    }

    private static String blockKey(String world, int x, int y, int z) {
        return world + ':' + x + ':' + y + ':' + z;
    }

    private record ClearBlock(int x, int y, int z) {
    }

    private static final class PreviewBlock {
        private final String world;
        private final int x;
        private final int y;
        private final int z;
        private BlockData originalData;
        private boolean active;
        private boolean removed;

        private PreviewBlock(String world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private String key() {
            return blockKey(world, x, y, z);
        }
    }
}
