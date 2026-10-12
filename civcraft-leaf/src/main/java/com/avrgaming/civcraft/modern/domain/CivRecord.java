package com.avrgaming.civcraft.modern.domain;

import java.util.UUID;

public record CivRecord(long id, String name, String tag, UUID leaderUuid, long coins, String government) {
}
