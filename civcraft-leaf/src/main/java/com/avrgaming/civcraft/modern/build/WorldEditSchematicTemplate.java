package com.avrgaming.civcraft.modern.build;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.block.BaseBlock;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

public final class WorldEditSchematicTemplate {
    private final Path path;
    private final List<SchematicBlock> blocks;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int nonAirBlocks;

    private WorldEditSchematicTemplate(Path path, List<SchematicBlock> blocks, int sizeX, int sizeY, int sizeZ, int nonAirBlocks) {
        this.path = path;
        this.blocks = List.copyOf(blocks);
        this.sizeX = Math.max(1, sizeX);
        this.sizeY = Math.max(1, sizeY);
        this.sizeZ = Math.max(1, sizeZ);
        this.nonAirBlocks = Math.max(0, nonAirBlocks);
    }

    public static WorldEditSchematicTemplate load(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        ClipboardFormat format = ClipboardFormats.findByFile(normalized.toFile());
        if (format == null) {
            throw new IOException("Unsupported schematic format: " + normalized);
        }

        try (InputStream input = Files.newInputStream(normalized); ClipboardReader reader = format.getReader(input)) {
            Clipboard clipboard = reader.read();
            Region region = clipboard.getRegion();
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            int sizeX = max.getBlockX() - min.getBlockX() + 1;
            int sizeY = max.getBlockY() - min.getBlockY() + 1;
            int sizeZ = max.getBlockZ() - min.getBlockZ() + 1;
            List<SchematicBlock> blocks = new ArrayList<>();
            int nonAir = 0;

            for (BlockVector3 vector : region) {
                BaseBlock block = clipboard.getFullBlock(vector);
                boolean air = block.getBlockType().getMaterial().isAir();
                if (!air) {
                    nonAir++;
                }
                BlockData blockData = blockData(block, air);
                blocks.add(new SchematicBlock(
                        vector.getBlockX() - min.getBlockX(),
                        vector.getBlockY() - min.getBlockY(),
                        vector.getBlockZ() - min.getBlockZ(),
                        air,
                        blockData,
                        block.getBlockType().getId()
                ));
            }

            return new WorldEditSchematicTemplate(normalized, blocks, sizeX, sizeY, sizeZ, nonAir);
        }
    }

    private static BlockData blockData(BaseBlock block, boolean air) {
        if (air) {
            return Bukkit.createBlockData("minecraft:air");
        }
        try {
            return Bukkit.createBlockData(block.toImmutableState().getAsString());
        } catch (IllegalArgumentException ignored) {
            return Bukkit.createBlockData(block.getBlockType().getId());
        }
    }

    public List<BlockPlacement> paste(Location origin, boolean includeAir) throws Exception {
        World world = origin.getWorld();
        if (world == null) {
            throw new IOException("World for schematic paste is missing.");
        }
        ClipboardFormat format = ClipboardFormats.findByFile(path.toFile());
        if (format == null) {
            throw new IOException("Unsupported schematic format: " + path);
        }

        try (InputStream input = Files.newInputStream(path); ClipboardReader reader = format.getReader(input)) {
            Clipboard clipboard = reader.read();
            clipboard.setOrigin(clipboard.getRegion().getMinimumPoint());
            try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
                Operation operation = new ClipboardHolder(clipboard)
                        .createPaste(editSession)
                        .to(BlockVector3.at(origin.getBlockX(), origin.getBlockY(), origin.getBlockZ()))
                        .ignoreAirBlocks(!includeAir)
                        .build();
                Operations.complete(operation);
            }
        }

        List<BlockPlacement> placements = new ArrayList<>(nonAirBlocks);
        for (SchematicBlock block : blocks) {
            if (block.air()) {
                continue;
            }
            placements.add(new BlockPlacement(
                    world.getName(),
                    origin.getBlockX() + block.x(),
                    origin.getBlockY() + block.y(),
                    origin.getBlockZ() + block.z(),
                    block.materialId()
            ));
        }
        return List.copyOf(placements);
    }

    public Set<String> structureKeys(String world, int originX, int originY, int originZ, boolean includeAir) {
        Set<String> keys = new HashSet<>();
        for (SchematicBlock block : blocks) {
            if (!includeAir && block.air()) {
                continue;
            }
            keys.add(world + ':' + (originX + block.x()) + ':' + (originY + block.y()) + ':' + (originZ + block.z()));
        }
        return keys;
    }

    public Path path() {
        return path;
    }

    public List<SchematicBlock> blocks() {
        return blocks;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public int nonAirBlocks() {
        return nonAirBlocks;
    }

    public record SchematicBlock(int x, int y, int z, boolean air, BlockData blockData, String materialId) {
    }
}
