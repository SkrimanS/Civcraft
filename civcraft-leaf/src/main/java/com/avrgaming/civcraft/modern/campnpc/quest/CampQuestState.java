package com.avrgaming.civcraft.modern.campnpc.quest;

import java.util.ArrayList;
import java.util.List;

public final class CampQuestState {
    public final long campId;
    public List<String> offerIds;
    public List<String> activeIds;
    public long generatedAt;
    public long lastRerollAt;

    public CampQuestState(long campId, List<String> offerIds, List<String> activeIds, long generatedAt, long lastRerollAt) {
        this.campId = campId;
        this.offerIds = new ArrayList<>(offerIds);
        this.activeIds = new ArrayList<>(activeIds);
        this.generatedAt = generatedAt;
        this.lastRerollAt = lastRerollAt;
    }
}
