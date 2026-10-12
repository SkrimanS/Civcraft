package com.avrgaming.civcraft.modern.campnpc.fancy;

import com.avrgaming.civcraft.modern.campnpc.bridge.CampMarker;
import com.avrgaming.civcraft.modern.campnpc.bridge.CivCraftBridge;
import com.avrgaming.civcraft.modern.campnpc.gui.MenuManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.java.JavaPlugin;

public final class FancyNpcService {
    private final JavaPlugin plugin;
    private final CivCraftBridge civCraft;
    private final MenuManager menus;
    private final Map<String, Long> npcNameToCamp = new HashMap<>();
    private final Listener dynamicListener = new Listener() {};
    private boolean npcEnabled;
    private boolean lookAtNearestPlayer;
    private String npcDisplayName;
    private String npcSkin;
    private String npcSkinTexture;
    private String npcSkinSignature;
    private long syncIntervalTicks;
    private int syncTaskId = -1;

    public FancyNpcService(JavaPlugin plugin, CivCraftBridge civCraft, MenuManager menus) {
        this.plugin = plugin;
        this.civCraft = civCraft;
        this.menus = menus;
    }

    public void start() {
        reloadConfigValues();
        registerFancyNpcEvent();
        syncTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::syncSafe, 40L, syncIntervalTicks).getTaskId();
        Bukkit.getScheduler().runTaskLater(plugin, this::syncSafe, 20L * 12L);
    }

    public void shutdown() {
        if (syncTaskId != -1) {
            Bukkit.getScheduler().cancelTask(syncTaskId);
        }
    }

    public void forceSync() {
        reloadConfigValues();
        syncSafe();
    }

    private void reloadConfigValues() {
        this.npcEnabled = plugin.getConfig().getBoolean("camp-npc.npc.enabled", plugin.getConfig().getBoolean("npc.enabled", true));
        this.lookAtNearestPlayer = plugin.getConfig().getBoolean("camp-npc.npc.look-at-nearest-player", plugin.getConfig().getBoolean("npc.look-at-nearest-player", false));
        this.npcDisplayName = firstConfigString("camp-npc.npc.display-name", "npc.display-name", "<gold>{camp_name}</gold>");
        this.npcSkin = firstConfigString("camp-npc.npc.skin", "npc.skin", "");
        this.npcSkinTexture = firstConfigString("camp-npc.npc.skin-texture", "npc.skin-texture", "");
        this.npcSkinSignature = firstConfigString("camp-npc.npc.skin-signature", "npc.skin-signature", "");
        long seconds = Math.max(1L, plugin.getConfig().getLong("camp-npc.sync-interval-seconds", plugin.getConfig().getLong("sync-interval-seconds", 1L)));
        this.syncIntervalTicks = seconds * 20L;
    }

    private void registerFancyNpcEvent() {
        try {
            Class<?> eventClass = Class.forName("de.oliver.fancynpcs.api.events.NpcInteractEvent");
            @SuppressWarnings("unchecked")
            Class<? extends Event> typed = (Class<? extends Event>) eventClass.asSubclass(Event.class);
            EventExecutor executor = (listener, event) -> handleNpcInteract(event);
            Bukkit.getPluginManager().registerEvent(typed, dynamicListener, EventPriority.NORMAL, executor, plugin, true);
            plugin.getLogger().info("FancyNpcs interact event hooked.");
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("FancyNpcs NpcInteractEvent not found. NPC click menus will not work: " + exception.getMessage());
        }
    }

    private void handleNpcInteract(Event event) {
        Player player = extractPlayer(event);
        String npcName = extractNpcName(event);
        if (player == null || npcName == null) {
            return;
        }
        Long campId = npcNameToCamp.get(npcName);
        if (campId == null && npcName.startsWith("civcraft_camp_npc_")) {
            try {
                campId = Long.parseLong(npcName.substring("civcraft_camp_npc_".length()));
            } catch (NumberFormatException ignored) {
            }
        }
        if (campId == null) {
            return;
        }
        long selectedCampId = campId;
        CampMarker marker = civCraft.getMarkers().stream().filter(m -> m.campId() == selectedCampId).findFirst().orElse(null);
        String campName = marker == null ? String.valueOf(selectedCampId) : marker.campName();
        menus.openMain(player, selectedCampId, campName);
    }

    private Player extractPlayer(Event event) {
        for (String methodName : List.of("getPlayer", "player")) {
            try {
                Object value = event.getClass().getMethod(methodName).invoke(event);
                if (value instanceof Player player) {
                    return player;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private String extractNpcName(Event event) {
        try {
            Object npc = event.getClass().getMethod("getNpc").invoke(event);
            if (npc == null) {
                return null;
            }
            Object data = callOptional(npc, "getData");
            if (data != null) {
                Object name = callOptional(data, "getName");
                if (name != null) {
                    return String.valueOf(name);
                }
            }
            Object name = callOptional(npc, "getName");
            return name == null ? null : String.valueOf(name);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private Object callOptional(Object target, String method) {
        try {
            return invokeNoArgs(target, method);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private void syncSafe() {
        try {
            sync();
        } catch (Exception exception) {
            plugin.getLogger().warning("Unable to sync camp NPCs: " + exception.getMessage());
        }
    }

    private void sync() throws ReflectiveOperationException {
        if (!civCraft.available()) {
            civCraft.reload();
            if (!civCraft.available()) {
                return;
            }
        }
        if (!npcEnabled) {
            for (String existing : new HashSet<>(npcNameToCamp.keySet())) {
                removeNpc(existing);
                npcNameToCamp.remove(existing);
            }
            return;
        }
        List<CampMarker> markers = civCraft.getMarkers();
        Set<String> expectedNames = new HashSet<>();
        for (CampMarker marker : markers) {
            if (!civCraft.isCampActive(marker.campId())) {
                continue;
            }
            World world = Bukkit.getWorld(marker.world());
            if (world == null) {
                continue;
            }
            String npcName = npcName(marker.campId());
            expectedNames.add(npcName);
            npcNameToCamp.put(npcName, marker.campId());
            Object existingNpc = getNpc(npcName);
            if (existingNpc == null) {
                createNpc(marker, world, npcName);
            } else {
                applyNpcOptions(existingNpc, marker);
            }
        }
        for (String existing : new HashSet<>(npcNameToCamp.keySet())) {
            if (!expectedNames.contains(existing)) {
                removeNpc(existing);
                npcNameToCamp.remove(existing);
            }
        }
    }

    private String npcName(long campId) {
        return "civcraft_camp_npc_" + campId;
    }

    private void createNpc(CampMarker marker, World world, String npcName) throws ReflectiveOperationException {
        Class<?> pluginClass = Class.forName("de.oliver.fancynpcs.api.FancyNpcsPlugin");
        Class<?> dataClass = Class.forName("de.oliver.fancynpcs.api.NpcData");
        Object fancyPlugin = invokeStaticNoArgs(pluginClass, "get");
        Object manager = invokeNoArgs(fancyPlugin, "getNpcManager");
        Object adapter = invokeNoArgs(fancyPlugin, "getNpcAdapter");
        Location location = new Location(world, marker.x() + 0.5, marker.y(), marker.z() + 0.5);
        Constructor<?> constructor = dataClass.getConstructor(String.class, UUID.class, Location.class);
        Object data = constructor.newInstance(npcName, plugin.getServer().getConsoleSender().getName().isEmpty() ? UUID.randomUUID() : UUID.nameUUIDFromBytes(npcName.getBytes()), location);
        applyDataOptions(data, marker);
        Object npc = invokeOneArg(adapter, "apply", data);
        callIfExists(npc, "setSaveToFile", new Class<?>[]{boolean.class}, false);
        invokeOneArg(manager, "registerNpc", npc);
        invokeNoArgs(npc, "create");
        invokeNoArgs(npc, "spawnForAll");
        plugin.getLogger().info("Spawned camp menu NPC for camp " + marker.campId());
    }

    private void applyNpcOptions(Object npc, CampMarker marker) {
        Object data = callOptional(npc, "getData");
        if (data == null) {
            return;
        }
        applyDataOptions(data, marker);
        callIfExists(npc, "updateForAll", new Class<?>[]{});
        callIfExists(npc, "update", new Class<?>[]{});
    }

    private void applyDataOptions(Object data, CampMarker marker) {
        String displayName = npcDisplayName == null ? "" : npcDisplayName
                .replace("{camp_name}", marker.campName())
                .replace("{camp_id}", String.valueOf(marker.campId()));
        callIfExists(data, "setDisplayName", new Class<?>[]{String.class}, displayName);
        if (npcSkin != null && !npcSkin.isBlank()) {
            callIfExists(data, "setSkin", new Class<?>[]{String.class}, npcSkin);
        }
        if (npcSkinTexture != null && !npcSkinTexture.isBlank()) {
            callIfExists(data, "setSkinTexture", new Class<?>[]{String.class}, npcSkinTexture);
            callIfExists(data, "setTexture", new Class<?>[]{String.class}, npcSkinTexture);
        }
        if (npcSkinSignature != null && !npcSkinSignature.isBlank()) {
            callIfExists(data, "setSkinSignature", new Class<?>[]{String.class}, npcSkinSignature);
            callIfExists(data, "setSignature", new Class<?>[]{String.class}, npcSkinSignature);
        }
        callIfExists(data, "setTurnToPlayer", new Class<?>[]{boolean.class}, lookAtNearestPlayer);
        callIfExists(data, "setLookAtPlayer", new Class<?>[]{boolean.class}, lookAtNearestPlayer);
        callIfExists(data, "setLookAtNearestPlayer", new Class<?>[]{boolean.class}, lookAtNearestPlayer);
        callIfExists(data, "setTrackNearestPlayer", new Class<?>[]{boolean.class}, lookAtNearestPlayer);
    }

    private String firstConfigString(String primary, String legacyPath, String fallback) {
        String value = plugin.getConfig().getString(primary);
        if (value != null) {
            return value;
        }
        return plugin.getConfig().getString(legacyPath, fallback);
    }

    private Object getNpc(String name) throws ReflectiveOperationException {
        Class<?> pluginClass = Class.forName("de.oliver.fancynpcs.api.FancyNpcsPlugin");
        Object fancyPlugin = invokeStaticNoArgs(pluginClass, "get");
        Object manager = invokeNoArgs(fancyPlugin, "getNpcManager");
        return invokeOneArg(manager, "getNpc", name);
    }

    private void removeNpc(String name) {
        try {
            Object npc = getNpc(name);
            if (npc == null) {
                return;
            }
            Class<?> pluginClass = Class.forName("de.oliver.fancynpcs.api.FancyNpcsPlugin");
            Object fancyPlugin = invokeStaticNoArgs(pluginClass, "get");
            Object manager = invokeNoArgs(fancyPlugin, "getNpcManager");
            invokeBestRemove(manager, npc);
            invokeNoArgs(npc, "removeForAll");
            plugin.getLogger().info("Removed camp menu NPC " + name);
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Unable to remove FancyNPC " + name + ": " + exception.getMessage());
        }
    }

    private Object invokeOneArg(Object target, String name, Object arg) throws ReflectiveOperationException {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> parameter = method.getParameterTypes()[0];
            if (arg == null || parameter.isAssignableFrom(arg.getClass()) || parameter == Object.class || parameter.isInterface()) {
                method.setAccessible(true);
                return method.invoke(target, arg);
            }
        }
        throw new NoSuchMethodException(name + "(" + (arg == null ? "null" : arg.getClass().getName()) + ")");
    }

    private void invokeBestRemove(Object manager, Object npc) throws ReflectiveOperationException {
        for (Method method : manager.getClass().getMethods()) {
            if (!method.getName().equals("removeNpc") || method.getParameterCount() != 1) {
                continue;
            }
            if (method.getParameterTypes()[0].isAssignableFrom(npc.getClass()) || method.getParameterTypes()[0].isInterface()) {
                method.setAccessible(true);
                method.invoke(manager, npc);
                return;
            }
        }
        throw new NoSuchMethodException("removeNpc(npc)");
    }

    private Object invokeStaticNoArgs(Class<?> type, String name) throws ReflectiveOperationException {
        Method method = type.getMethod(name);
        method.setAccessible(true);
        return method.invoke(null);
    }

    private Object invokeNoArgs(Object target, String name) throws ReflectiveOperationException {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) {
                method.setAccessible(true);
                return method.invoke(target);
            }
        }
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) {
                method.setAccessible(true);
                return method.invoke(target);
            }
        }
        throw new NoSuchMethodException(name + "()");
    }

    private void callIfExists(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method method = target.getClass().getMethod(name, types);
            method.setAccessible(true);
            method.invoke(target, args);
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
