package com.avrgaming.civcraft.modern.campnpc.quest;

import java.util.List;
import org.bukkit.configuration.ConfigurationSection;

public record QuestDefinition(
        String id,
        String title,
        String description,
        int level,
        QuestObjective objective,
        ConfigurationSection icon,
        List<QuestReward> rewards
) {
    public int target() {
        return objective.target();
    }

    public List<ItemMatcher> acceptedItems() {
        return objective.acceptedItems();
    }
}
