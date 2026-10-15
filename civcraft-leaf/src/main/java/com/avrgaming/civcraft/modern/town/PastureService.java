package com.avrgaming.civcraft.modern.town;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.io.File;
import java.sql.SQLException;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.*;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;

/** Original manual animal breeding with a persistent shared offspring limit for each pasture. */
public final class PastureService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final NamespacedKey identity;
    private final int maximum;
    private boolean ready,paused;
    private long lastWarning,lastLocations;
    public PastureService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException{
        this.plugin=plugin;this.storage=storage;identity=new NamespacedKey(plugin,"pasture-origin");
        maximum=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/structures.yml")).getInt("pasture.max_mobs");if(maximum<0)throw new IllegalArgumentException("Invalid pasture maximum");
        storage.transaction(db->{PastureStore.install(db);return null;});plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    public int maximum(){return maximum;}
    private OptionalLong at(Location location)throws SQLException{return storage.query(db->PastureStore.at(db,location.getWorld().getName(),Math.floorDiv(location.getBlockX(),16),Math.floorDiv(location.getBlockZ(),16)));}
    private void error(Player player,String text){player.sendMessage(Component.text(text));}
    private void warn(Exception failure){if(System.currentTimeMillis()-lastWarning>30000){lastWarning=System.currentTimeMillis();plugin.getLogger().severe("Pasture persistence failed: "+failure.getMessage());}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void feed(PlayerInteractEntityEvent event){
        if(!(event.getRightClicked() instanceof Animals animal))return;
        var stack=event.getPlayer().getInventory().getItem(event.getHand());if(stack==null || !animal.isBreedItem(stack))return;
        String type=animal.getType().name();if(PastureRules.horse(type)){event.setCancelled(true);error(event.getPlayer(),"Разведение лошадей запрещено.");return;}
        if(!PastureRules.controlled(type))return;
        try{
            if(!ready || paused)throw new IllegalArgumentException("Учёт животных восстанавливается. Подождите немного.");
            var pasture=at(event.getPlayer().getLocation());if(pasture.isEmpty())throw new IllegalArgumentException("Разводить этих животных можно только на пастбище.");
            if(!storage.query(db->PastureStore.active(db,pasture.getAsLong())))throw new IllegalArgumentException("Пастбище не работает или разрушено.");
            if(!PastureRules.room(storage.query(db->PastureStore.count(db,pasture.getAsLong())),0,maximum))throw new IllegalArgumentException("Пастбище заполнено. Освободите место для потомства.");
            if(animal.isLoveMode())event.setCancelled(true);
        }catch(IllegalArgumentException failure){event.setCancelled(true);error(event.getPlayer(),failure.getMessage());}catch(SQLException failure){event.setCancelled(true);warn(failure);}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void breed(EntityBreedEvent event){
        try{
            String type=event.getEntityType().name();var pasture=at(event.getEntity().getLocation());
            if(PastureRules.horse(type))throw new IllegalArgumentException("Разведение лошадей запрещено.");
            if(pasture.isEmpty()){if(PastureRules.controlled(type))throw new IllegalArgumentException("Разводить этих животных можно только на пастбище.");return;}
            if(!ready || paused)throw new IllegalArgumentException("Учёт животных восстанавливается. Подождите немного.");
            var entity=event.getEntity();var location=entity.getLocation();
            storage.transaction(db->PastureStore.reserve(db,entity.getUniqueId(),pasture.getAsLong(),location.getWorld().getName(),Math.floorDiv(location.getBlockX(),16),Math.floorDiv(location.getBlockZ(),16),type,maximum));
            entity.getPersistentDataContainer().set(identity,PersistentDataType.LONG,pasture.getAsLong());
        }catch(IllegalArgumentException failure){event.setCancelled(true);if(event.getBreeder() instanceof Player player)error(player,failure.getMessage());}catch(SQLException failure){event.setCancelled(true);warn(failure);}
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void born(EntityBreedEvent event){
        plugin.getServer().getScheduler().runTask(plugin,()->{
            try{
                var entity=event.getEntity();if(event.isCancelled()){storage.transaction(db->{PastureStore.cancel(db,entity.getUniqueId());return null;});entity.getPersistentDataContainer().remove(identity);return;}
                if(entity.isValid() && !paused)saveBirth(entity);
            }catch(SQLException|RuntimeException failure){warn(failure);}
        });
    }
    private void saveBirth(LivingEntity entity)throws SQLException{
        var record=storage.query(db->PastureStore.find(db,entity.getUniqueId()));if(record==null || !record.state().equals("PENDING"))return;
        entity.getPersistentDataContainer().set(identity,PersistentDataType.LONG,record.pasture());locate(entity);
        entity.getWorld().save(true);
        if(record.pasture()==Long.getLong("civcraft.test.pasture.build",-1) && "entity-saved".equals(System.getProperty("civcraft.test.pasture.interruption"))){paused=true;plugin.getLogger().warning("Injected pasture interruption at entity-saved");return;}
        storage.transaction(db->PastureStore.confirm(db,entity.getUniqueId()));
    }
    private void locate(Entity entity)throws SQLException{
        var location=entity.getLocation();storage.transaction(db->{PastureStore.locate(db,entity.getUniqueId(),location.getWorld().getName(),Math.floorDiv(location.getBlockX(),16),Math.floorDiv(location.getBlockZ(),16));return null;});
    }
    private void recognize(Entity entity)throws SQLException{
        Long pasture=entity.getPersistentDataContainer().get(identity,PersistentDataType.LONG);if(pasture==null)return;
        if(!storage.query(db->PastureStore.exists(db,pasture))){entity.getPersistentDataContainer().remove(identity);return;}
        var location=entity.getLocation();storage.transaction(db->{PastureStore.restore(db,entity.getUniqueId(),pasture,location.getWorld().getName(),Math.floorDiv(location.getBlockX(),16),Math.floorDiv(location.getBlockZ(),16),entity.getType().name());return null;});
    }
    public void tick(){
        if(paused)return;
        try{
            boolean recovered=true;
            for(var record:storage.query(db->PastureStore.animals(db,true))){
                var world=Bukkit.getWorld(record.world());if(world==null){recovered=false;continue;}
                var chunk=world.getChunkAt(record.chunkX(),record.chunkZ());Entity found=null;
                for(var entity:chunk.getEntities())if(entity.getUniqueId().equals(record.uuid())){found=entity;break;}
                if(found instanceof LivingEntity living && living.isValid())saveBirth(living);
                else if(chunk.isEntitiesLoaded())storage.transaction(db->{PastureStore.cancel(db,record.uuid());return null;});else recovered=false;
                if(paused)return;
            }
            if(!ready && recovered){for(var world:Bukkit.getWorlds())for(var entity:world.getEntities())recognize(entity);ready=true;}
            if(System.currentTimeMillis()-lastLocations>30000){lastLocations=System.currentTimeMillis();
                storage.transaction(db->{PastureStore.releaseDemolished(db);return null;});
                for(var record:storage.query(db->PastureStore.animals(db,false))){var entity=Bukkit.getEntity(record.uuid());if(entity!=null && entity.isValid()){recognize(entity);locate(entity);}}
            }
        }catch(SQLException|RuntimeException failure){warn(failure);}
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void loaded(EntitiesLoadEvent event){for(var entity:event.getEntities())try{recognize(entity);}catch(SQLException|RuntimeException failure){warn(failure);}}
    @EventHandler(priority=EventPriority.MONITOR)
    public void unloaded(EntitiesUnloadEvent event){for(var entity:event.getEntities())if(entity.getPersistentDataContainer().has(identity,PersistentDataType.LONG))try{locate(entity);}catch(SQLException|RuntimeException failure){warn(failure);}}
    @EventHandler(priority=EventPriority.MONITOR)
    public void death(EntityDeathEvent event){remove(event.getEntity());}
    @EventHandler(priority=EventPriority.MONITOR)
    public void removed(EntityRemoveEvent event){if(event.getCause()==EntityRemoveEvent.Cause.UNLOAD){try{if(event.getEntity().getPersistentDataContainer().has(identity,PersistentDataType.LONG))locate(event.getEntity());}catch(SQLException failure){warn(failure);}}else remove(event.getEntity());}
    private void remove(Entity entity){if(!entity.getPersistentDataContainer().has(identity,PersistentDataType.LONG))return;try{storage.transaction(db->{PastureStore.remove(db,entity.getUniqueId());return null;});}catch(SQLException failure){warn(failure);}}
}
