package com.avrgaming.civcraft.modern.war;

import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import java.sql.SQLException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityExplodeEvent;

/** Original peace gate and durable wartime TNT/creeper event intake. */
public final class LegacyExplosionService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final LegacyExplosionImpact impact;
    private final int radius,damage;
    private long lastWarning;
    private int requestCursor;
    private final java.util.Set<Long> stoppedConstructions=new java.util.HashSet<>();
    public LegacyExplosionService(CivCraftModernPlugin plugin,StorageBootstrap storage) {
        this.plugin=plugin;this.storage=storage;
        var config=YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("civcraft/data/war.yml").toFile());
        radius=config.getInt("tnt.yield",3);damage=config.getInt("tnt.structure_damage",20);
        if(radius<0 || radius>64 || damage<0)throw new IllegalArgumentException("Неверные настройки радиуса или урона военного TNT.");
        try{storage.transaction(db->{LegacyExplosionStore.install(db);return null;});}catch(SQLException failure){throw new IllegalStateException("Cannot install legacy explosions",failure);}
        impact=new LegacyExplosionImpact(plugin);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,1L,1L);
    }
    @EventHandler(priority=EventPriority.LOWEST)
    public void entityExplosion(EntityExplodeEvent event) {
        try {
            Long war=storage.query(db->LegacyExplosionStore.activeWar(db,System.currentTimeMillis()));
            if(war==null){event.setCancelled(true);return;}
            var entity=event.getEntity();
            if(!(entity instanceof org.bukkit.entity.TNTPrimed) && !(entity instanceof org.bukkit.entity.minecart.ExplosiveMinecart) && !(entity instanceof org.bukkit.entity.Creeper))return;
            event.setCancelled(true);
            var at=event.getLocation();String id="tnt:"+war+":"+event.getEntity().getUniqueId();
            var request=new LegacyExplosionStore.Request(id,war,at.getWorld().getName(),at.getBlockX(),at.getBlockY(),at.getBlockZ(),radius,damage);
            storage.transaction(db->{LegacyExplosionStore.receive(db,request);return null;});
            impact.prepare(request);
        }catch(SQLException|RuntimeException failure) {
            event.setCancelled(true);
            warn(failure);
        }
    }
    private void tick() {
        try {
            storage.transaction(db->{LegacyExplosionStore.expire(db,System.currentTimeMillis());return null;});
            var stopped=storage.query(db->{var out=new java.util.ArrayList<Long>();try(var s=db.createStatement();var rows=s.executeQuery("SELECT DISTINCT h.build_id FROM legacy_explosion_hits h JOIN structure_builds b ON b.id=h.build_id WHERE h.stopped_construction=1 AND b.status IN ('WAR_DESTROY_PENDING','WAR_DESTROYED')")){while(rows.next())out.add(rows.getLong(1));}return out;});
            for(long build:stopped)if(stoppedConstructions.add(build))plugin.legacyStructures().stopSabotagedBuild(build);
            var pending=storage.query(LegacyExplosionStore::pending);if(pending.isEmpty()){requestCursor=0;return;}
            int start=Math.floorMod(requestCursor,pending.size()),count=Math.min(4,pending.size());
            for(int i=0;i<count;i++)try{impact.prepare(pending.get((start+i)%pending.size()));}catch(SQLException|RuntimeException failure){warn(failure);}
            requestCursor=(start+count)%pending.size();
        }catch(SQLException|RuntimeException failure){warn(failure);}
    }
    private void warn(Exception failure){long now=System.currentTimeMillis();if(now-lastWarning>=30000){lastWarning=now;plugin.getLogger().severe("Legacy explosion retained: "+failure.getMessage());}}
}
