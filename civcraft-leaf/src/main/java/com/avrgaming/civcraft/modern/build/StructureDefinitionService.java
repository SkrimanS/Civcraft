package com.avrgaming.civcraft.modern.build;

import com.avrgaming.civcraft.modern.config.ModernCivCraftSettings;
import java.io.File;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class StructureDefinitionService {
    private final JavaPlugin plugin;
    private final Map<String, StructureDefinition> definitions = new LinkedHashMap<>();
    private ModernCivCraftSettings settings;
    private final Map<String,StaticAttributes> attributes=new LinkedHashMap<>();
    private final Map<String,Integer> templateYShifts=new LinkedHashMap<>();

    public StructureDefinitionService(JavaPlugin plugin, ModernCivCraftSettings settings) {
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
        attributes.clear();
        templateYShifts.clear();
        File file = new File(settings.structureConfigPath());
        if (!file.isFile()) {
            plugin.getLogger().warning("Structure config not found: " + file.getAbsolutePath());
            return;
        }
        load(file, "structures");
        File wonders = new File(file.getParentFile(), "wonders.yml");
        if (wonders.isFile()) {
            load(wonders, "wonders");
        } else {
            plugin.getLogger().warning("Wonder config not found: " + wonders.getAbsolutePath());
        }
        plugin.getLogger().info("Loaded " + definitions.size() + " CivCraft structure and wonder definitions.");
    }

    private void load(File file, String section) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> raw : yaml.getMapList(section)) {
            String id = string(raw.get("id"));
            String template = string(raw.get("template"));
            if (id.isBlank() || template.isBlank()) {
                continue;
            }
            StaticAttributes stats=StaticAttributes.parse(raw.get("components"));
            attributes.put(id.toLowerCase(Locale.ROOT),stats);
            templateYShifts.put(id.toLowerCase(Locale.ROOT),(int)number(raw.get("template_y_shift"),0));
            StructureDefinition definition = new StructureDefinition(
                    id,
                    template,
                    stringOr(raw.get("displayName"), id),
                    number(raw.get("cost"), 0.0),
                    number(raw.get("upkeep"), 0.0),
                    number(raw.get("hammer_cost"), 0.0),
                    number(raw.get("hammers_per_hour"), 0.0)+stats.get("HAMMERS"),
                    number(raw.get("beakers_per_hour"), 0.0)+stats.get("BEAKERS"),
                    number(raw.get("money_per_hour"), 0.0)+stats.get("MONEY"),
                    (int)number(raw.get("happiness"),0)+stats.happiness(),
                    (int) number(raw.get("max_hitpoints"), 1.0),
                    string(raw.get("require_tech")),
                    Math.max(0, (int) number(raw.get("limit"), 0.0)),
                    Boolean.parseBoolean(String.valueOf(raw.containsKey("strategic") ? raw.get("strategic") : false))
            );
            definitions.put(id.toLowerCase(Locale.ROOT), definition);
        }
    }

    public Optional<StructureDefinition> find(String id) {
        if(id==null)return Optional.empty();
        return Optional.ofNullable(definitions.get(id.toLowerCase(Locale.ROOT)));
    }
    public StaticAttributes attributes(String id){return id==null?new StaticAttributes(Map.of()):attributes.getOrDefault(id.toLowerCase(Locale.ROOT),new StaticAttributes(Map.of()));}
    public int templateYShift(String id){return templateYShifts.getOrDefault(id.toLowerCase(Locale.ROOT),0);}

    public List<StructureDefinition> list(int limit) {
        return definitions.values().stream()
                .sorted(Comparator.comparing(StructureDefinition::id))
                .limit(limit)
                .toList();
    }

    private int happiness(Map<?, ?> raw, String id) {
        if (raw.containsKey("happiness")) {
            return (int) number(raw.get("happiness"), 0.0);
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        if (normalized.equals("s_townhall") || normalized.equals(settings.townHallStructureId().toLowerCase(Locale.ROOT))) {
            return 2;
        }
        if (normalized.equals("s_capitol") || normalized.equals(settings.capitolStructureId().toLowerCase(Locale.ROOT))) {
            return 4;
        }
        return 0;
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
