package com.avrgaming.civcraft.modern.build;

import java.util.List;

public record LegacyBlock(int x, int y, int z, int legacyId, byte legacyData, List<String> signLines) {
    public boolean isAir() {
        return legacyId == 0;
    }
}
