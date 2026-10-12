package com.avrgaming.civcraft.modern.domain;

import java.util.UUID;

public record TownClaimRecord(String world, int chunkX, int chunkZ, long townId, long civId, UUID claimedBy, long claimedAt) {
}
