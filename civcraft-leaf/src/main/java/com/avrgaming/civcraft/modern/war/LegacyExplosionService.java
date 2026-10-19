package com.avrgaming.civcraft.modern.war;

import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.SQLException;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** The always-registered original WarListener cancels entity blasts outside war. */
public final class LegacyExplosionService implements Listener {
    private final JavaPlugin plugin;
    private final StorageBootstrap storage;
    private long lastWarning;
    public LegacyExplosionService(JavaPlugin plugin,StorageBootstrap storage) {
        this.plugin=plugin;this.storage=storage;
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    @EventHandler(priority=EventPriority.LOWEST)
    public void entityExplosion(EntityExplodeEvent event) {
        try {
            if(!storage.query(db->WarClockStore.active(db,System.currentTimeMillis())))event.setCancelled(true);
            // The active-war TNT/creeper replacement is a separate source path;
            // it must use persistent terrain/health intents rather than this gate.
        }catch(SQLException|RuntimeException failure) {
            event.setCancelled(true);
            long now=System.currentTimeMillis();if(now-lastWarning>=30000){lastWarning=now;plugin.getLogger().severe("Unable to check legacy explosion war state: "+failure.getMessage());}
        }
    }
}
