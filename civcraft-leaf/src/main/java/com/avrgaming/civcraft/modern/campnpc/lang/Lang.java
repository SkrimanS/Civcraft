package com.avrgaming.civcraft.modern.campnpc.lang;

import com.avrgaming.civcraft.modern.campnpc.util.Text;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class Lang {
    private final JavaPlugin plugin;
    private YamlConfiguration config;

    public Lang(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "lang/ru.yml"));
    }

    public String raw(String key, String fallback) {
        return config.getString(key, fallback);
    }

    public String msg(String key, String fallback, Object... replacements) {
        String message = raw(key, fallback);
        Map<String, String> placeholders = new HashMap<>();
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            placeholders.put(String.valueOf(replacements[i]), String.valueOf(replacements[i + 1]));
        }
        return Text.apply(message, placeholders).replace('&', '§');
    }


    public List<String> list(String key, List<String> fallback, Object... replacements) {
        List<String> source = config.getStringList(key);
        if (source == null || source.isEmpty()) {
            source = fallback == null ? List.of() : fallback;
        }
        Map<String, String> placeholders = new HashMap<>();
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            placeholders.put(String.valueOf(replacements[i]), String.valueOf(replacements[i + 1]));
        }
        List<String> result = new ArrayList<>();
        for (String line : source) {
            result.add(Text.apply(line, placeholders).replace('&', '§'));
        }
        return result;
    }

    public Component component(String key, String fallback, Object... replacements) {
        return Text.component(msg(key, fallback, replacements));
    }
}
