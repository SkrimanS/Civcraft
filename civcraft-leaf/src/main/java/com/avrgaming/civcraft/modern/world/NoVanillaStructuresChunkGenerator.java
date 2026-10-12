package com.avrgaming.civcraft.modern.world;

import java.util.Random;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

/**
 * Vanilla-terrain passthrough generator with vanilla structures disabled.
 *
 * Use it only for new worlds through bukkit.yml:
 * worlds:
 *   world:
 *     generator: CivCraft
 */
@SuppressWarnings("deprecation")
public final class NoVanillaStructuresChunkGenerator extends ChunkGenerator {

    public boolean shouldGenerateNoise() {
        return true;
    }

    public boolean shouldGenerateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return true;
    }

    public boolean shouldGenerateSurface() {
        return true;
    }

    public boolean shouldGenerateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return true;
    }

    public boolean shouldGenerateBedrock() {
        return true;
    }

    public boolean shouldGenerateBedrock(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return true;
    }

    public boolean shouldGenerateCaves() {
        return true;
    }

    public boolean shouldGenerateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkY, int chunkZ) {
        return true;
    }

    public boolean shouldGenerateDecorations() {
        return true;
    }

    public boolean shouldGenerateDecorations(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return true;
    }

    public boolean shouldGenerateMobs() {
        return true;
    }

    public boolean shouldGenerateMobs(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return true;
    }

    public boolean shouldGenerateStructures() {
        return false;
    }

    public boolean shouldGenerateStructures(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return false;
    }
}
