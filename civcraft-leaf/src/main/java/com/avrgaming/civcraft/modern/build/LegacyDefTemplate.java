package com.avrgaming.civcraft.modern.build;

import java.util.List;

public record LegacyDefTemplate(int sizeX, int sizeY, int sizeZ, List<LegacyBlock> blocks) {
    public int nonAirBlocks() {
        int count = 0;
        for (LegacyBlock block : blocks) {
            if (!block.isAir()) {
                count++;
            }
        }
        return count;
    }
}
