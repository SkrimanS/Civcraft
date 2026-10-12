package com.avrgaming.civcraft.modern.campnpc.quest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public record QuestReward(String type, double amount, String value, ConfigurationSection item, List<String> commands) {
    public static QuestReward from(ConfigurationSection section) {
        if (section == null) {
            return new QuestReward("NONE", 0D, "", new YamlConfiguration(), List.of());
        }
        String type = section.getString("type", "COMMAND").trim().toUpperCase(Locale.ROOT);
        double amount = section.getDouble("amount", section.getDouble("value", 0D));
        String value = section.getString("value", section.getString("upgrade", section.getString("bonus", "")));
        ConfigurationSection item = section.getConfigurationSection("item");
        if (item == null) {
            YamlConfiguration copy = new YamlConfiguration();
            for (String key : section.getKeys(false)) {
                copy.set(key, section.get(key));
            }
            item = copy;
        }
        List<String> commands = new ArrayList<>(section.getStringList("commands"));
        String command = section.getString("command", null);
        if (command != null && !command.isBlank()) {
            commands.add(command);
        }
        return new QuestReward(type, amount, value == null ? "" : value, item, commands);
    }

    public static List<QuestReward> listFrom(ConfigurationSection quest) {
        List<QuestReward> rewards = new ArrayList<>();
        if (quest.isList("rewards")) {
            for (Map<?, ?> raw : quest.getMapList("rewards")) {
                YamlConfiguration section = new YamlConfiguration();
                for (Map.Entry<?, ?> entry : raw.entrySet()) {
                    section.set(String.valueOf(entry.getKey()), entry.getValue());
                }
                rewards.add(from(section));
            }
        }
        for (String command : quest.getStringList("reward-commands")) {
            YamlConfiguration section = new YamlConfiguration();
            section.set("type", "COMMAND");
            section.set("command", command);
            rewards.add(from(section));
        }
        return rewards;
    }
}
