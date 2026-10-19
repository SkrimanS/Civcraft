package com.avrgaming.civcraft.modern.town;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.war.WarClockStore;
import java.sql.SQLException;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.event.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;

/** Original global spawn restrictions and the per-plot mobs flag. */
public final class CreatureSpawnService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final NamespacedKey purchased;
    private long lastWarning;
    public CreatureSpawnService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException{
        this.plugin=plugin;this.storage=storage;purchased=new NamespacedKey(plugin,"stable-purchase");
        storage.transaction(db->{PlotStore.installClaims(db);return null;});
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        // Paper reports chunk-loaded entities through EntitiesLoadEvent rather than a DEFAULT spawn.
        plugin.getServer().getScheduler().runTask(plugin,()->{
            for(var world:plugin.getServer().getWorlds())for(var entity:world.getEntities())checkLoadedHorse(entity);
        });
    }
    private void warn(SQLException failure){
        long now=System.currentTimeMillis();if(now-lastWarning>=30000){lastWarning=now;plugin.getLogger().severe("Creature spawn rules unavailable: "+failure.getMessage());}
    }
    private boolean marked(Entity entity){return entity.getPersistentDataContainer().has(purchased,PersistentDataType.STRING);}
    private boolean building(Entity entity,String structure)throws SQLException{
        var location=entity.getLocation();
        return storage.query(db->{
            try(var s=db.prepareStatement("SELECT 1 FROM structure_chunks c JOIN active_structure_builds b ON b.id=c.build_id WHERE c.world=? AND c.chunk_x=? AND c.chunk_z=? AND b.structure_id=? AND b.status='COMPLETE' LIMIT 1")){
                s.setString(1,location.getWorld().getName());s.setInt(2,Math.floorDiv(location.getBlockX(),16));s.setInt(3,Math.floorDiv(location.getBlockZ(),16));
                s.setString(4,structure);
                try(var r=s.executeQuery()){return r.next();}
            }
        });
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void spawn(CreatureSpawnEvent event){
        try{
            String type=event.getEntityType().name();
            boolean war=storage.query(db->WarClockStore.active(db,System.currentTimeMillis()));
            if(CreatureSpawnRules.denied(type,event.getSpawnReason().name(),war)){event.setCancelled(true);return;}
            if(CreatureSpawnRules.horse(type)){
                if(marked(event.getEntity()) || building(event.getEntity(),"s_stable"))return;
                if(event.getSpawnReason()==CreatureSpawnEvent.SpawnReason.DEFAULT){
                    // Stable creation may attach its persistent identity after the spawn callback.
                    var entity=event.getEntity();plugin.getServer().getScheduler().runTask(plugin,()->checkLoadedHorse(entity));
                }else event.setCancelled(true);
                return;
            }
            if(event.getSpawnReason()==CreatureSpawnEvent.SpawnReason.CUSTOM || !CreatureSpawnRules.restricted(type))return;
            var location=event.getLocation();var key=new PlotStore.Key(location.getWorld().getName(),Math.floorDiv(location.getBlockX(),16),Math.floorDiv(location.getBlockZ(),16));
            if(!storage.query(db->PlotStore.mobs(db,key)) && !building(event.getEntity(),"w_battledome"))event.setCancelled(true);
        }catch(SQLException failure){event.setCancelled(true);warn(failure);}
    }
    private void checkLoadedHorse(Entity entity){
        if(!CreatureSpawnRules.horse(entity.getType().name()) || !entity.isValid() || marked(entity))return;
        try{if(!building(entity,"s_stable"))entity.remove();}catch(SQLException failure){warn(failure);}
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void loaded(EntitiesLoadEvent event){
        for(var entity:event.getEntities())if(CreatureSpawnRules.horse(entity.getType().name()))
            plugin.getServer().getScheduler().runTask(plugin,()->checkLoadedHorse(entity));
    }
}
