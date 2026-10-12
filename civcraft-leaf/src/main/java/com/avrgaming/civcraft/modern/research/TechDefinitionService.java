package com.avrgaming.civcraft.modern.research;

import com.avrgaming.civcraft.modern.config.ModernCivCraftSettings;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class TechDefinitionService {
    private final JavaPlugin plugin;
    private final Map<String, TechDefinition> definitions = new LinkedHashMap<>();
    private ModernCivCraftSettings settings;

    public TechDefinitionService(JavaPlugin plugin, ModernCivCraftSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        reload();
    }

    public void updateSettings(ModernCivCraftSettings settings) {
        this.settings = settings;
        reload();
    }

    public void reload() {
        definitions.clear();
        File file = new File(settings.techConfigPath());
        if (!file.isFile()) {
            File dataFolderFile = plugin.getDataFolder().toPath().resolve(settings.techConfigPath()).toFile();
            if (dataFolderFile.isFile()) {
                file = dataFolderFile;
            }
        }
        if (!file.isFile()) {
            plugin.getLogger().warning("Tech config not found: " + file.getAbsolutePath());
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> raw : yaml.getMapList("techs")) {
            String id = string(raw.get("id"));
            if (id.isBlank()) {
                continue;
            }
            TechDefinition definition = new TechDefinition(
                    id,
                    stringOr(raw.get("name"), id),
                    number(raw.get("beaker_cost"), 0.0),
                    number(raw.get("cost"), 0.0),
                    (int) number(raw.get("points"), 0.0),
                    splitRequirements(raw.get("require_techs")),
                    (int) number(raw.get("era"), 0.0)
            );
            definitions.put(id.toLowerCase(Locale.ROOT), definition);
        }
        plugin.getLogger().info("Loaded " + definitions.size() + " CivCraft technology definitions.");
    }

    public Optional<TechDefinition> find(String id) {
        return Optional.ofNullable(definitions.get(id.toLowerCase(Locale.ROOT)));
    }

    public List<TechDefinition> list(int limit) {
        return definitions.values().stream()
                .sorted(Comparator.comparingInt(TechDefinition::era).thenComparing(TechDefinition::id))
                .limit(limit)
                .toList();
    }

    private List<String> splitRequirements(Object value) {
        String raw = string(value);
        if (raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String item = part.trim();
            if (!item.isBlank()) {
                out.add(item);
            }
        }
        return List.copyOf(out);
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String stringOr(Object value, String fallback) {
        String string = string(value);
        return string.isBlank() ? fallback : string;
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
