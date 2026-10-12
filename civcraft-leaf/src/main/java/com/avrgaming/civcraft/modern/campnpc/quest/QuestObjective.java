package com.avrgaming.civcraft.modern.campnpc.quest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

public final class QuestObjective {
    private final String type;
    private final int target;
    private final List<ItemMatcher> acceptedItems;
    private final List<String> mythicMobs;
    private final List<EntityType> entityTypes;
    private final List<LocationTarget> locations;

    public QuestObjective(String type, int target, List<ItemMatcher> acceptedItems, List<String> mythicMobs, List<EntityType> entityTypes, List<LocationTarget> locations) {
        this.type = type == null || type.isBlank() ? "ITEM_DELIVERY" : type.trim().toUpperCase(Locale.ROOT);
        this.target = Math.max(1, target);
        this.acceptedItems = List.copyOf(acceptedItems);
        this.mythicMobs = mythicMobs.stream().filter(value -> value != null && !value.isBlank()).map(value -> value.toLowerCase(Locale.ROOT)).toList();
        this.entityTypes = List.copyOf(entityTypes);
        this.locations = List.copyOf(locations);
    }

    public static QuestObjective from(ConfigurationSection quest) {
        ConfigurationSection objective = quest.getConfigurationSection("objective");
        String type = objective == null ? quest.getString("type", "ITEM_DELIVERY") : objective.getString("type", "ITEM_DELIVERY");
        int target = objective == null ? quest.getInt("target", 1) : objective.getInt("target", quest.getInt("target", 1));

        List<ItemMatcher> items = new ArrayList<>();
        ConfigurationSection itemRoot = objective == null ? quest : objective;
        if (itemRoot.isList("accepted-items")) {
            for (Map<?, ?> raw : itemRoot.getMapList("accepted-items")) {
                items.add(ItemMatcher.from(mapToSection(raw)));
            }
        } else if (itemRoot.isList("items")) {
            for (Map<?, ?> raw : itemRoot.getMapList("items")) {
                items.add(ItemMatcher.from(mapToSection(raw)));
            }
        } else {
            ConfigurationSection acceptedItem = itemRoot.getConfigurationSection("accepted-item");
            if (acceptedItem != null) {
                items.add(ItemMatcher.from(acceptedItem));
            }
        }
        if (items.isEmpty() && "ITEM_DELIVERY".equalsIgnoreCase(type)) {
            Material material = Material.matchMaterial(itemRoot.getString("material", "AIR"));
            if (material != null && !material.isAir()) {
                YamlConfiguration single = new YamlConfiguration();
                single.set("material", material.name());
                if (itemRoot.contains("custom-model-data")) {
                    single.set("custom-model-data", itemRoot.getInt("custom-model-data"));
                }
                if (itemRoot.contains("itemsadder")) {
                    single.set("itemsadder", itemRoot.getString("itemsadder"));
                }
                items.add(ItemMatcher.from(single));
            }
        }

        List<String> mythicMobs = itemRoot.getStringList("mythicmobs");
        if (mythicMobs.isEmpty()) {
            mythicMobs = itemRoot.getStringList("mythic-mobs");
        }
        List<EntityType> entityTypes = new ArrayList<>();
        for (String raw : itemRoot.getStringList("entity-types")) {
            try {
                entityTypes.add(EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }

        List<LocationTarget> locations = new ArrayList<>();
        for (Map<?, ?> raw : itemRoot.getMapList("locations")) {
            LocationTarget targetLocation = LocationTarget.from(mapToSection(raw));
            if (targetLocation != null) {
                locations.add(targetLocation);
            }
        }
        ConfigurationSection location = itemRoot.getConfigurationSection("location");
        if (location != null) {
            LocationTarget targetLocation = LocationTarget.from(location);
            if (targetLocation != null) {
                locations.add(targetLocation);
            }
        }

        return new QuestObjective(type, target, items, mythicMobs, entityTypes, locations);
    }

    private static ConfigurationSection mapToSection(Map<?, ?> raw) {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            config.set(String.valueOf(entry.getKey()), entry.getValue());
        }
        return config;
    }

    public String type() {
        return type;
    }

    public int target() {
        return target;
    }

    public List<ItemMatcher> acceptedItems() {
        return acceptedItems;
    }

    public boolean isItemDelivery() {
        return "ITEM_DELIVERY".equals(type) || "ITEM".equals(type) || "ITEMS".equals(type);
    }

    public boolean isMobKill() {
        return "MOB_KILL".equals(type) || "KILL".equals(type) || "MYTHICMOBS_KILL".equals(type);
    }

    public boolean isLocationVisit() {
        return "LOCATION_VISIT".equals(type) || "LOCATION".equals(type) || "VISIT".equals(type);
    }


    public List<LocationTarget> locations() {
        return locations;
    }

    public String locationSummary() {
        if (locations.isEmpty()) {
            return "-";
        }
        List<String> parts = new ArrayList<>();
        for (LocationTarget target : locations) {
            parts.add(target.display());
        }
        return String.join(", ", parts);
    }

    public boolean matchesMob(String mythicMobId, EntityType entityType) {
        if (!isMobKill()) {
            return false;
        }
        if (mythicMobId != null && !mythicMobId.isBlank()) {
            String clean = mythicMobId.toLowerCase(Locale.ROOT);
            if (mythicMobs.contains(clean)) {
                return true;
            }
        }
        return entityType != null && entityTypes.contains(entityType);
    }

    public boolean matchesLocation(Location location) {
        if (!isLocationVisit()) {
            return false;
        }
        for (LocationTarget targetLocation : locations) {
            if (targetLocation.matches(location)) {
                return true;
            }
        }
        return false;
    }

    public record LocationTarget(String world, double x, double y, double z, double radius) {
        public static LocationTarget from(ConfigurationSection section) {
            if (section == null || !section.contains("world")) {
                return null;
            }
            return new LocationTarget(
                    section.getString("world", "world"),
                    section.getDouble("x"),
                    section.getDouble("y"),
                    section.getDouble("z"),
                    Math.max(0.5D, section.getDouble("radius", 3.0D))
            );
        }

        public String display() {
            return world + " " + clean(x) + " " + clean(y) + " " + clean(z) + " (радиус " + clean(radius) + ")";
        }

        private String clean(double value) {
            if (Math.rint(value) == value) {
                return String.valueOf((int) value);
            }
            return String.format(Locale.ROOT, "%.1f", value);
        }

        public boolean matches(Location location) {
            World bukkitWorld = location.getWorld();
            if (bukkitWorld == null || !bukkitWorld.getName().equals(world)) {
                return false;
            }
            return location.distanceSquared(new Location(bukkitWorld, x, y, z)) <= radius * radius;
        }
    }
}
