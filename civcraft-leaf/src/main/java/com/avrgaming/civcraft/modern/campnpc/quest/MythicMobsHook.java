package com.avrgaming.civcraft.modern.campnpc.quest;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

public final class MythicMobsHook {
    public Optional<String> idOf(Entity entity) {
        if (entity == null || !Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) {
            return Optional.empty();
        }
        try {
            Class<?> mythicBukkitClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Method inst = mythicBukkitClass.getMethod("inst");
            Object mythicBukkit = inst.invoke(null);
            Object mobManager = mythicBukkit.getClass().getMethod("getMobManager").invoke(mythicBukkit);
            Object activeMobOptional = mobManager.getClass().getMethod("getActiveMob", UUID.class).invoke(mobManager, entity.getUniqueId());
            if (!(activeMobOptional instanceof Optional<?> optional) || optional.isEmpty()) {
                return Optional.empty();
            }
            Object activeMob = optional.get();
            Object type = activeMob.getClass().getMethod("getType").invoke(activeMob);
            Object id = type.getClass().getMethod("getInternalName").invoke(type);
            return Optional.ofNullable(String.valueOf(id));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return Optional.empty();
        }
    }
}
