package com.avrgaming.civcraft.modern.campnpc.reward;

import com.avrgaming.civcraft.modern.campnpc.bridge.CivCraftBridge;
import com.avrgaming.civcraft.modern.campnpc.bridge.PlayerCampInfo;
import com.avrgaming.civcraft.modern.campnpc.storage.PluginStorage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class CampBonusApplier implements Listener {
    private static final double DEFAULT_MAX_HEALTH = 20.0D;
    private static final double DEFAULT_ATTACK_DAMAGE = 1.0D;

    private final JavaPlugin plugin;
    private final PluginStorage storage;
    private final CivCraftBridge civCraft;
    private final Set<String> warnedMissingAttributes = ConcurrentHashMap.newKeySet();
    private volatile Attribute maxHealthAttribute;
    private volatile Attribute attackDamageAttribute;

    public CampBonusApplier(JavaPlugin plugin, PluginStorage storage, CivCraftBridge civCraft) {
        this.plugin = plugin;
        this.storage = storage;
        this.civCraft = civCraft;
    }

    public void start() {
        this.maxHealthAttribute = resolveAttribute(
                new String[] {"MAX_HEALTH", "GENERIC_MAX_HEALTH"},
                new String[] {"max_health", "generic.max_health"}
        );
        this.attackDamageAttribute = resolveAttribute(
                new String[] {"ATTACK_DAMAGE", "GENERIC_ATTACK_DAMAGE"},
                new String[] {"attack_damage", "generic.attack_damage"}
        );
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::applyAllOnline, 40L, 20L * 2L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> apply(event.getPlayer()), 20L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> apply(event.getPlayer()), 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        reset(event.getPlayer());
    }

    public void applyAllOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            apply(player);
        }
    }

    public void resetAllOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            reset(player);
        }
    }

    public void apply(Player player) {
        Optional<PlayerCampInfo> camp = civCraft.getPlayerCamp(player.getUniqueId());
        if (camp.isEmpty() || !civCraft.isCampActive(camp.get().campId())) {
            reset(player);
            return;
        }
        CampBonuses bonuses = bonuses(camp.get().campId());
        double maxHealth = DEFAULT_MAX_HEALTH + Math.max(0D, bonuses.healthBonus());
        double attackDamage = DEFAULT_ATTACK_DAMAGE + Math.max(0D, bonuses.damageBonus());
        setBase(player, maxHealthAttribute, "max health", maxHealth);
        setBase(player, attackDamageAttribute, "attack damage", attackDamage);
        if (player.getHealth() > maxHealth) {
            player.setHealth(Math.max(1D, maxHealth));
        }
    }

    public void reset(Player player) {
        setBase(player, maxHealthAttribute, "max health", DEFAULT_MAX_HEALTH);
        setBase(player, attackDamageAttribute, "attack damage", DEFAULT_ATTACK_DAMAGE);
        if (player.getHealth() > DEFAULT_MAX_HEALTH) {
            player.setHealth(DEFAULT_MAX_HEALTH);
        }
    }

    public CampBonuses bonuses(long campId) {
        try (Connection connection = storage.connect(); PreparedStatement statement = connection.prepareStatement("SELECT health_bonus, damage_bonus FROM camp_npc_bonuses WHERE camp_id = ?")) {
            statement.setLong(1, campId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return new CampBonuses(rs.getDouble("health_bonus"), rs.getDouble("damage_bonus"));
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to load camp bonuses: " + exception.getMessage());
        }
        return new CampBonuses(0D, 0D);
    }

    private void setBase(Player player, Attribute attribute, String label, double value) {
        if (attribute == null) {
            warnMissingOnce(label);
            return;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null && Math.abs(instance.getBaseValue() - value) > 0.001D) {
            instance.setBaseValue(value);
        }
    }

    private void warnMissingOnce(String label) {
        if (warnedMissingAttributes.add(label)) {
            plugin.getLogger().warning("Unable to find Bukkit attribute for " + label + ". Camp bonuses will not apply correctly on this server API.");
        }
    }

    private Attribute resolveAttribute(String[] constantNames, String[] minecraftKeys) {
        for (String name : constantNames) {
            Attribute byField = attributeByStaticField(name);
            if (byField != null) {
                return byField;
            }
            Attribute byEnum = attributeByEnumName(name);
            if (byEnum != null) {
                return byEnum;
            }
        }
        for (String key : minecraftKeys) {
            Attribute byRegistry = attributeByRegistryKey(key);
            if (byRegistry != null) {
                return byRegistry;
            }
        }
        return null;
    }

    private Attribute attributeByStaticField(String name) {
        try {
            Field field = Attribute.class.getField(name);
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof Attribute attribute ? attribute : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Attribute attributeByEnumName(String name) {
        try {
            if (!Attribute.class.isEnum()) {
                return null;
            }
            Class enumClass = Attribute.class;
            Object value = Enum.valueOf(enumClass, name);
            return value instanceof Attribute attribute ? attribute : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Attribute attributeByRegistryKey(String key) {
        try {
            Class<?> namespacedKeyClass = Class.forName("org.bukkit.NamespacedKey");
            Method minecraft = namespacedKeyClass.getMethod("minecraft", String.class);
            Object namespacedKey = minecraft.invoke(null, key);

            Class<?> registryClass = Class.forName("org.bukkit.Registry");
            Field attributeRegistryField = registryClass.getField("ATTRIBUTE");
            Object attributeRegistry = attributeRegistryField.get(null);
            Method get = attributeRegistry.getClass().getMethod("get", namespacedKeyClass);
            get.setAccessible(true);
            Object value = get.invoke(attributeRegistry, namespacedKey);
            return value instanceof Attribute attribute ? attribute : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return null;
        }
    }

    public record CampBonuses(double healthBonus, double damageBonus) {
    }
}
