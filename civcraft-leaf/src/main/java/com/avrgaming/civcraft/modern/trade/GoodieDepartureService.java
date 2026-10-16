package com.avrgaming.civcraft.modern.trade;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.SQLException;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Departure drops can be materialized without an online player after a crash. */
public final class GoodieDepartureService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final BonusGoodieItems items;
    private final NamespacedKey dropKey;
    private final Set<UUID> departing=new HashSet<>();
    private boolean paused;
    private long lastWarning;
    public GoodieDepartureService(CivCraftModernPlugin plugin,StorageBootstrap storage,BonusGoodieItems items)throws SQLException {
        this.plugin=plugin;this.storage=storage;this.items=items;dropKey=new NamespacedKey(plugin,"goodie-drop");
        storage.transaction(db->{GoodieDepartureStore.install(db);return null;});departing.addAll(storage.query(GoodieDepartureStore::requested));
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,20L,20L);
    }
    public void requireUnlocked(UUID player){if(departing.contains(player))throw new IllegalArgumentException("Выпадение торговых товаров ещё сохраняется.");}
    private void warn(Exception failure){long now=System.currentTimeMillis();if(now-lastWarning<30000)return;lastWarning=now;plugin.getLogger().severe("Goodie departure recovery failed: "+failure.getMessage());}
    private void request(Player player,String reason)throws SQLException {
        var at=player.getLocation();var drop=new GoodieTransferStore.Drop(at.getWorld().getName(),at.getX(),at.getY(),at.getZ());
        if(storage.transaction(db->GoodieDepartureStore.request(db,player.getUniqueId(),drop,reason,System.currentTimeMillis())))departing.add(player.getUniqueId());
        process(player);
    }
    private void process(Player player)throws SQLException {
        if(!departing.contains(player.getUniqueId()))return;
        if(storage.transaction(db->GoodieDepartureStore.process(db,player.getUniqueId()))){departing.remove(player.getUniqueId());if(!player.isDead())purge(player);}
    }
    /** Old revisions never become valid again, including restored pre-death playerdata. */
    private void purge(Player player)throws SQLException {
        var inventory=player.getInventory().getContents();var seen=new HashSet<String>();boolean changed=false;
        if(items.token(player.getItemOnCursor())==null && Arrays.stream(inventory).noneMatch(item->items.token(item)!=null))return;
        if(storage.query(db->GoodieDepartureStore.waiting(db,player.getUniqueId())))return;
        for(int i=0;i<inventory.length;i++)if(!valid(player,inventory[i],seen)){inventory[i]=null;changed=true;}
        if(changed)player.getInventory().setContents(inventory);
        if(!valid(player,player.getItemOnCursor(),seen)){player.setItemOnCursor(null);changed=true;}
        if(changed)player.saveData();
    }
    private boolean valid(Player player,ItemStack item,Set<String> seen)throws SQLException {
        String token=items.token(item);if(token==null)return true;
        var state=storage.query(db->BonusGoodieStore.token(db,token));
        return state!=null && items.matches(item,state) && state.kind()==BonusGoodieStore.Kind.PLAYER && state.holder().equals(player.getUniqueId().toString()) && seen.add(token);
    }
    private void interrupt(String phase,GoodieDepartureStore.Job job) {
        if(job.player().toString().equals(System.getProperty("civcraft.test.goodie.departure.player")) && phase.equals(System.getProperty("civcraft.test.goodie.departure.interruption"))){paused=true;throw new IllegalStateException("Injected departure interruption at "+phase);}
    }
    private void materialize(GoodieDepartureStore.Job job)throws SQLException {
        var actual=storage.query(db->BonusGoodieStore.origin(db,job.good().origin()));
        if(!job.good().equals(actual)){storage.transaction(db->{GoodieDepartureStore.phase(db,job.id(),"DONE");return null;});return;}
        if(storage.query(db->GoodieTransferStore.pending(db).stream().anyMatch(p->p.before().origin()==job.good().origin())))return;
        var world=Bukkit.getWorld(job.at().world());if(world==null)return;
        var at=new Location(world,job.at().x(),job.at().y(),job.at().z());var chunk=world.getChunkAt(at.getBlockX()>>4,at.getBlockZ()>>4);
        if(!chunk.isEntitiesLoaded())return;
        Item found=null;
        for(var item:world.getEntitiesByClass(Item.class))if(job.id().equals(item.getPersistentDataContainer().get(dropKey,PersistentDataType.STRING))) {
            if(found==null && items.matches(item.getItemStack(),job.good()))found=item;else item.remove();
        }
        if(found==null && job.phase().equals("ACTIVE")) {
            // A previously saved drop disappeared outside our normal despawn/combust hooks.
            storage.transaction(db->{var current=BonusGoodieStore.origin(db,job.good().origin());if(job.good().equals(current))BonusGoodieStore.returnHome(db,current);GoodieDepartureStore.phase(db,job.id(),"DONE");return null;});return;
        }
        if(found==null)world.dropItem(at,items.create(job.good()),item->{item.getPersistentDataContainer().set(dropKey,PersistentDataType.STRING,job.id());item.setPickupDelay(10);});
        if(job.phase().equals("PREPARED")){world.save(true);interrupt("ground-saved",job);storage.transaction(db->{GoodieDepartureStore.phase(db,job.id(),"ACTIVE");return null;});}
    }
    private void tick() {
        if(paused)return;
        try {
            // Requests blocked by another inventory journal remain durable until its recovery.
            for(UUID player:List.copyOf(departing))if(storage.transaction(db->GoodieDepartureStore.process(db,player)))departing.remove(player);
            for(var player:Bukkit.getOnlinePlayers())if(!player.isDead())purge(player);
            for(var job:storage.query(GoodieDepartureStore::jobs))materialize(job);
        }catch(Exception failure){warn(failure);}
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void death(PlayerDeathEvent event) {
        if(event.getKeepInventory())return;
        try {
            request(event.getEntity(),"DEATH");
            event.getDrops().removeIf(item->items.token(item)!=null);event.getItemsToKeep().removeIf(item->items.token(item)!=null);
        }catch(Exception failure){
            // If no durable intent could be committed, retain the cargo rather than
            // leaving an unregistered entity that the canonical-item scanner removes.
            var cargo=new ArrayList<ItemStack>();for(var item:event.getDrops())if(items.token(item)!=null)cargo.add(item);
            event.getDrops().removeIf(item->items.token(item)!=null);event.getItemsToKeep().addAll(cargo);warn(failure);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void quit(PlayerQuitEvent event){if(paused)return;try{request(event.getPlayer(),"QUIT");}catch(Exception failure){warn(failure);}}
    @EventHandler(priority=EventPriority.HIGHEST)
    public void join(PlayerJoinEvent event){if(paused)return;try{process(event.getPlayer());purge(event.getPlayer());}catch(Exception failure){warn(failure);}}
    @EventHandler(priority=EventPriority.MONITOR)
    public void respawn(PlayerRespawnEvent event){plugin.getServer().getScheduler().runTask(plugin,()->{try{process(event.getPlayer());purge(event.getPlayer());}catch(Exception failure){warn(failure);}});}
}
