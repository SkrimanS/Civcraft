package com.avrgaming.civcraft.modern.build;

import java.nio.file.Path;

public record QueuedLegacyBuild(
        long id,
        Path path,
        int queuedBlocks,
        int sizeX,
        int sizeY,
        int sizeZ,
        Long townId,
        double totalHammers,
        double hammersPerHour,
        long durationMillis
) {
}
