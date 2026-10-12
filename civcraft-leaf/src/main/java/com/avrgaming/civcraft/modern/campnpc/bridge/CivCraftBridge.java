package com.avrgaming.civcraft.modern.campnpc.bridge;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class CivCraftBridge {
    private final JavaPlugin plugin;
    private Plugin civCraftPlugin;
    private Object api;

    public CivCraftBridge(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.civCraftPlugin = Bukkit.getPluginManager().getPlugin("CivCraft");
        this.api = null;
        if (civCraftPlugin == null) {
            plugin.getLogger().warning("CivCraft не найден. NPC лагерей не будут работать.");
            return;
        }
        try {
            this.api = civCraftPlugin.getClass().getMethod("getCampApi").invoke(civCraftPlugin);
            plugin.getLogger().info("CivCraft Camp API connected.");
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("CivCraft найден, но getCampApi() недоступен: " + exception.getMessage());
        }
    }

    public boolean available() {
        return api != null;
    }

    public List<CampMarker> getMarkers() {
        if (api == null) {
            return List.of();
        }
        try {
            Object raw = api.getClass().getMethod("getCampNpcMarkers").invoke(api);
            if (!(raw instanceof Iterable<?> iterable)) {
                return List.of();
            }
            List<CampMarker> result = new ArrayList<>();
            for (Object marker : iterable) {
                long campId = asLong(call(marker, "campId"));
                String campName = String.valueOf(call(marker, "campName"));
                String world = String.valueOf(call(marker, "world"));
                int x = asInt(call(marker, "x"));
                int y = asInt(call(marker, "y"));
                int z = asInt(call(marker, "z"));
                result.add(new CampMarker(campId, campName, world, x, y, z));
            }
            return result;
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Unable to read CivCraft camp NPC markers: " + exception.getMessage());
            return List.of();
        }
    }

    public boolean isCampActive(long campId) {
        if (api == null) {
            return false;
        }
        try {
            Object value = api.getClass().getMethod("isCampActive", long.class).invoke(api, campId);
            return Boolean.TRUE.equals(value);
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    public CampRole getCampRole(UUID uuid, long campId) {
        if (api == null) {
            return CampRole.NONE;
        }
        try {
            Object value = api.getClass().getMethod("getCampRole", UUID.class, long.class).invoke(api, uuid, campId);
            return CampRole.fromObject(value);
        } catch (ReflectiveOperationException exception) {
            return CampRole.NONE;
        }
    }

    public Optional<PlayerCampInfo> getPlayerCamp(UUID uuid) {
        if (api == null) {
            return Optional.empty();
        }
        try {
            Object value = api.getClass().getMethod("getPlayerCamp", UUID.class).invoke(api, uuid);
            if (!(value instanceof Optional<?> optional) || optional.isEmpty()) {
                return Optional.empty();
            }
            Object info = optional.get();
            long campId = asLong(call(info, "campId"));
            String campName = String.valueOf(call(info, "campName"));
            CampRole role = CampRole.fromObject(call(info, "role"));
            return Optional.of(new PlayerCampInfo(campId, campName, role));
        } catch (ReflectiveOperationException exception) {
            return Optional.empty();
        }
    }

    public int getCampLevel(long campId) {
        if (api == null) {
            return 1;
        }
        try {
            Method method = api.getClass().getMethod("getCampLevel", long.class);
            return Math.max(1, asInt(method.invoke(api, campId)));
        } catch (ReflectiveOperationException ignored) {
            return 1;
        }
    }


    public Optional<Location> getCampFoodChestLocation(long campId) {
        if (api == null) {
            return Optional.empty();
        }
        try {
            Object value = api.getClass().getMethod("getCampFoodChestLocation", long.class).invoke(api, campId);
            if (value instanceof Optional<?> optional && optional.isPresent() && optional.get() instanceof Location location) {
                return Optional.of(location);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return Optional.empty();
    }

    public Optional<UUID> getCampLeaderUuid(long campId) {
        if (api == null) {
            return Optional.empty();
        }
        try {
            Object value = api.getClass().getMethod("getCampLeaderUuid", long.class).invoke(api, campId);
            if (value instanceof Optional<?> optional && optional.isPresent() && optional.get() instanceof UUID uuid) {
                return Optional.of(uuid);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return Optional.empty();
    }

    public void addCampLeaderCoins(long campId, long amount) {
        invokeVoid("addCampLeaderCoins", new Class<?>[]{long.class, long.class}, campId, amount);
    }

    public void addCampExperience(long campId, int amount) {
        invokeVoid("addCampExperience", new Class<?>[]{long.class, int.class}, campId, amount);
    }

    public void addCampControlHp(long campId, int amount) {
        invokeVoid("addCampControlHp", new Class<?>[]{long.class, int.class}, campId, amount);
    }

    public void setCampUpgrade(long campId, String upgrade, boolean enabled) {
        invokeVoid("setCampUpgrade", new Class<?>[]{long.class, String.class, boolean.class}, campId, upgrade, enabled);
    }

    private void invokeVoid(String methodName, Class<?>[] parameterTypes, Object... args) {
        if (api == null) {
            return;
        }
        try {
            Method method = api.getClass().getMethod(methodName, parameterTypes);
            method.invoke(api, args);
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("CivCraftCampApi method failed: " + methodName + ": " + exception.getMessage());
        }
    }

    private Object call(Object target, String method) throws ReflectiveOperationException {
        return target.getClass().getMethod(method).invoke(target);
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
