package com.avrgaming.civcraft.modern.api;

public record CampMemberInfo(
        long campId,
        String campName,
        CampRole role
) {
}
