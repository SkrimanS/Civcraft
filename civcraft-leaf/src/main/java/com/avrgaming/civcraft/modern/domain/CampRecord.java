package com.avrgaming.civcraft.modern.domain;

import java.util.UUID;

public record CampRecord(long id, String name, UUID ownerUuid, String world, int x, int y, int z, int hitpoints, int firepointsHours) {
}
