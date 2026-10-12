package com.avrgaming.civcraft.modern.api;

public record CampNpcMarker(
        long campId,
        String campName,
        String world,
        int x,
        int y,
        int z
) {
}
