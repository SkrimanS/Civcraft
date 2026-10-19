package com.avrgaming.civcraft.modern.trade;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Item;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/** Ordinary overflow drops stay immobile and unavailable until the inventory intent commits. */
final class PlainTransferGround implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final NamespacedKey marker;
    private final Set<ChunkRef> tickets=new HashSet<>();
    private final Set<UUID> loaded=new HashSet<>();
    private final org.bukkit.scheduler.BukkitTask task;
    private boolean teleporting;
    private long lastWarning;
    private record ChunkRef(String world,int x,int z){}
    private record Drop(String operation,int ordinal,String world,double x,double y,double z,byte[] item,boolean pending) {
        String key(){return operation+"/"+ordinal;}
    }
    PlainTransferGround(CivCraftModernPlugin plugin,StorageBootstrap storage) {
        this.plugin=plugin;this.storage=storage;marker=new NamespacedKey(plugin,"ordinary-transfer-drop");
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        for(var world:Bukkit.getWorlds())for(var entity:world.getEntitiesByClass(Item.class))if(marked(entity))loaded.add(entity.getUniqueId());
        task=plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,1L,1L);
    }
    static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS ordinary_transfer_drops(operation_id TEXT NOT NULL REFERENCES goodie_chest_transfers(id),ordinal INTEGER NOT NULL,world TEXT NOT NULL,x REAL NOT NULL,y REAL NOT NULL,z REAL NOT NULL,item BLOB NOT NULL,released INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(operation_id,ordinal))");}
    }
    static void prepare(Connection db,String operation,Location at,List<ItemStack> items)throws SQLException {
        if(items.isEmpty())return;
        if(db.getAutoCommit())throw new SQLException("Overflow drops require a transaction");
        if(at==null || at.getWorld()==null || !Double.isFinite(at.getX()) || !Double.isFinite(at.getY()) || !Double.isFinite(at.getZ()) || items.size()>4096)throw new IllegalArgumentException("Invalid overflow drop plan");
        try(var s=db.prepareStatement("INSERT INTO ordinary_transfer_drops(operation_id,ordinal,world,x,y,z,item) VALUES(?,?,?,?,?,?,?)")) {
            int i=0;for(var item:items){if(GoodieChestPlan.empty(item) || item.getAmount()>item.getMaxStackSize())throw new IllegalArgumentException("Invalid overflow stack");s.setString(1,operation);s.setInt(2,i++);s.setString(3,at.getWorld().getName());s.setDouble(4,at.getX());s.setDouble(5,at.getY());s.setDouble(6,at.getZ());s.setBytes(7,ItemStack.serializeItemsAsBytes(new ItemStack[]{item}));s.addBatch();}s.executeBatch();
        }
    }
    private List<Drop> read(String operation)throws SQLException {
        return storage.query(db->{var out=new ArrayList<Drop>();String sql="SELECT d.*,t.status FROM ordinary_transfer_drops d JOIN goodie_chest_transfers t ON t.id=d.operation_id WHERE d.released=0"+(operation==null?"":" AND d.operation_id=?");
            try(var s=db.prepareStatement(sql)){if(operation!=null)s.setString(1,operation);try(var r=s.executeQuery()){while(r.next())out.add(new Drop(r.getString("operation_id"),r.getInt("ordinal"),r.getString("world"),r.getDouble("x"),r.getDouble("y"),r.getDouble("z"),r.getBytes("item"),r.getString("status").equals("PREPARED")));}}return out;});
    }
    private World world(Drop drop){var world=Bukkit.getWorld(drop.world());if(world==null)throw new IllegalStateException("Overflow world is unavailable: "+drop.world());return world;}
    private void pin(Drop drop) {
        var world=world(drop);int x=Location.locToBlock(drop.x())>>4,z=Location.locToBlock(drop.z())>>4;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){var ref=new ChunkRef(drop.world(),x+dx,z+dz);if(!tickets.contains(ref)){world.addPluginChunkTicket(ref.x(),ref.z(),plugin);tickets.add(ref);}}
    }
    private boolean marked(Item item){return item.getPersistentDataContainer().has(marker,PersistentDataType.STRING);}
    private Item find(Drop drop) {
        Item found=null;for(var item:world(drop).getEntitiesByClass(Item.class))if(drop.key().equals(item.getPersistentDataContainer().get(marker,PersistentDataType.STRING))){if(found==null)found=item;else item.remove();}return found;
    }
    private void hold(Item item,Drop drop) {
        item.setCanPlayerPickup(false);item.setCanMobPickup(false);item.setPickupDelay(32767);item.setUnlimitedLifetime(true);item.setWillAge(false);item.setInvulnerable(true);item.setGravity(false);item.setVelocity(new Vector());
        var at=new Location(world(drop),drop.x(),drop.y(),drop.z());
        if(item.getWorld()!=at.getWorld() || item.getLocation().distanceSquared(at)>0.0001){teleporting=true;try{if(!item.teleport(at))throw new IllegalStateException("Cannot retain overflow drop position");}finally{teleporting=false;}}
    }
    private void release(Item item) {
        item.setCanPlayerPickup(true);item.setCanMobPickup(true);item.setPickupDelay(10);item.setUnlimitedLifetime(false);item.setWillAge(true);item.setInvulnerable(false);item.setGravity(true);item.getPersistentDataContainer().remove(marker);
    }
    /** Called before the parent world's save and SQL completion, never after completion. */
    Set<World> ensure(String operation)throws SQLException {
        var worlds=new HashSet<World>();for(var drop:read(operation)) {
            if(!drop.pending())throw new IllegalStateException("Overflow intent is already complete");pin(drop);
            var item=find(drop);var stacks=ItemStack.deserializeItemsFromBytes(drop.item());
            if(stacks.length!=1 || GoodieChestPlan.empty(stacks[0]))throw new IllegalStateException("Invalid stored overflow stack");
            if(item==null)item=world(drop).dropItem(new Location(world(drop),drop.x(),drop.y(),drop.z()),stacks[0],spawned->{spawned.getPersistentDataContainer().set(marker,PersistentDataType.STRING,drop.key());hold(spawned,drop);});
            if(!item.isValid())throw new IllegalStateException("Overflow item spawn was rejected");
            if(!item.getItemStack().equals(stacks[0]))throw new IllegalStateException("Overflow contents changed during recovery");
            hold(item,drop);worlds.add(world(drop));
        }return worlds;
    }
    private void tick() {
        try {
            var rows=read(null);var wanted=new HashSet<ChunkRef>();var ready=new ArrayList<Drop>();var activeWorlds=new HashSet<World>();
            for(var drop:rows)try{pin(drop);ready.add(drop);activeWorlds.add(world(drop));int x=Location.locToBlock(drop.x())>>4,z=Location.locToBlock(drop.z())>>4;for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)wanted.add(new ChunkRef(drop.world(),x+dx,z+dz));}catch(RuntimeException failure){warn(failure);}
            var indexed=new HashMap<String,Item>();for(var world:activeWorlds)for(var item:world.getEntitiesByClass(Item.class))if(marked(item)){String key=item.getPersistentDataContainer().get(marker,PersistentDataType.STRING);if(indexed.putIfAbsent(key,item)!=null)item.remove();}
            var completed=new ArrayList<Drop>();var changedWorlds=new HashSet<World>();
            for(var drop:ready)try{var item=indexed.get(drop.key());if(drop.pending()){if(item!=null)hold(item,drop);continue;}if(item!=null)release(item);changedWorlds.add(world(drop));completed.add(drop);}catch(RuntimeException failure){warn(failure);}
            for(var world:changedWorlds)world.save(true);
            if(!completed.isEmpty())storage.transaction(db->{try(var s=db.prepareStatement("UPDATE ordinary_transfer_drops SET released=1,item=X'' WHERE operation_id=? AND ordinal=?")){for(var drop:completed){s.setString(1,drop.operation());s.setInt(2,drop.ordinal());s.addBatch();}s.executeBatch();}return null;});
            for(var id:List.copyOf(loaded)){var entity=Bukkit.getEntity(id);if(entity instanceof Item item && marked(item)) {
                String key=item.getPersistentDataContainer().get(marker,PersistentDataType.STRING);int slash=key.lastIndexOf('/');
                boolean complete=slash>0 && storage.query(db->{try(var s=db.prepareStatement("SELECT 1 FROM goodie_chest_transfers WHERE id=? AND status='COMPLETE'")){s.setString(1,key.substring(0,slash));try(var r=s.executeQuery()){return r.next();}}});
                if(complete){release(item);item.getWorld().save(true);}
            }loaded.remove(id);}
            for(var ref:List.copyOf(tickets))if(!wanted.contains(ref)){var world=Bukkit.getWorld(ref.world());if(world!=null)world.removePluginChunkTicket(ref.x(),ref.z(),plugin);tickets.remove(ref);}
        }catch(SQLException|RuntimeException failure){warn(failure);}
    }
    private void warn(Exception failure){long now=System.currentTimeMillis();if(now-lastWarning>30000){lastWarning=now;plugin.getLogger().severe("Overflow drop recovery retained: "+failure.getMessage());}}
    void close(){task.cancel();for(var ref:tickets){var world=Bukkit.getWorld(ref.world());if(world!=null)world.removePluginChunkTicket(ref.x(),ref.z(),plugin);}tickets.clear();}
    @EventHandler public void loaded(EntitiesLoadEvent event){for(var entity:event.getEntities())if(entity instanceof Item item && marked(item))loaded.add(item.getUniqueId());}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void pickup(EntityPickupItemEvent event){if(marked(event.getItem()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void hopper(InventoryPickupItemEvent event){if(marked(event.getItem()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void merge(ItemMergeEvent event){if(marked(event.getEntity()) || marked(event.getTarget()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void despawn(ItemDespawnEvent event){if(marked(event.getEntity()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void damage(EntityDamageEvent event){if(event.getEntity() instanceof Item item && marked(item))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void teleport(EntityTeleportEvent event){if(!teleporting && event.getEntity() instanceof Item item && marked(item))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void portal(EntityPortalEvent event){if(event.getEntity() instanceof Item item && marked(item))event.setCancelled(true);}
}
