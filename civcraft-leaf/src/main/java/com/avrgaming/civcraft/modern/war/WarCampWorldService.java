package com.avrgaming.civcraft.modern.war;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;

/** Apply a durable camp journal in bounded batches, then flush the world before committing. */
public final class WarCampWorldService implements Listener {
    private final CivCraftModernPlugin plugin;private final StorageBootstrap storage;private final NamespacedKey dropKey;
    private Long current;private boolean restoring,paused;private List<WarCampStore.Block> blocks;private int index;private long lastWarning,lastUnlock;
    private final Map<String,WarCampDropsStore.Drop> drops=new HashMap<>();
    public WarCampWorldService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;dropKey=new NamespacedKey(plugin,"warcamp-drop");
        storage.transaction(db->{WarCampStore.install(db);WarCampDropsStore.install(db);return null;});plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    private static String coordinate(String world,int x,int y,int z){return world+":"+x+":"+y+":"+z;}
    private World world(String name){var world=Bukkit.getWorld(name);if(world==null)throw new IllegalStateException("War camp world unavailable");return world;}
    private static org.bukkit.inventory.Inventory inventory(BlockState state) {
        return state instanceof Container container?container.getSnapshotInventory():null;
    }
    private void clear(Block block) {
        var state=block.getState();var inventory=inventory(state);if(inventory!=null){inventory.clear();state.update(true,false);}
    }
    public void tick() {
        if(paused)return;
        try {
            long now=System.currentTimeMillis();storage.transaction(db->{WarCampStore.scheduleRestoration(db,now);return null;});
            if(current==null) {
                current=storage.query(WarCampStore::nextRestoration);restoring=current!=null;
                if(current==null)current=storage.query(WarCampStore::nextPreparation);
                if(current!=null){long id=current;blocks=storage.query(db->WarCampStore.blocks(db,id));index=0;drops.clear();for(var drop:storage.query(db->WarCampDropsStore.list(db,id)))drops.put(coordinate(drop.world(),drop.x(),drop.y(),drop.z()),drop);}
            }
            if(current!=null) {
                long id=current;var camp=storage.query(db->WarCampStore.camp(db,id));
                if(!restoring&&camp.state().equals("RESTORING")){current=null;blocks=null;return;}
                int budget=256;
                while(index<blocks.size()&&budget-->0){var saved=blocks.get(index++);var block=world(saved.world()).getBlockAt(saved.x(),saved.y(),saved.z());
                    if(restoring)restore(id,block,saved);else{clear(block);block.setBlockData(Bukkit.createBlockData(saved.after()),false);}
                }
                if(index==blocks.size()) {
                    var worlds=new HashSet<String>();for(var saved:blocks)worlds.add(saved.world());for(var name:worlds)world(name).save(true);
                    interruption(restoring?"restore-world-saved":"build-world-saved",id);
                    storage.transaction(db->{if(restoring){WarCampDropsStore.finish(db,id);WarCampStore.restored(db,id,now);}else WarCampStore.activate(db,id,now);return null;});
                    if(!restoring)plugin.getServer().broadcast(Component.text("Военный лагерь #"+id+" готов."));
                    current=null;blocks=null;index=0;drops.clear();
                }
            }
            reconcileControls(now);
            if(now-lastUnlock>=1000){lastUnlock=now;unlockDrops();}
        }catch(Exception failure){if(!paused){current=null;blocks=null;index=0;drops.clear();}if(System.currentTimeMillis()-lastWarning>30000){lastWarning=System.currentTimeMillis();plugin.getLogger().severe("War camp world processing failed; journal retained: "+failure.getMessage());}}
    }
    private void restore(long camp,Block block,WarCampStore.Block saved)throws SQLException {
        String key=coordinate(saved.world(),saved.x(),saved.y(),saved.z());var drop=drops.get(key);var container=inventory(block.getState());
        if(drop==null&&(container!=null||saved.beforeNbt()!=null)) {
            ItemStack[] contents=container==null?new ItemStack[0]:container.getContents();byte[] encoded=ItemStack.serializeItemsAsBytes(contents);
            drop=storage.transaction(db->WarCampDropsStore.prepare(db,camp,saved.world(),saved.x(),saved.y(),saved.z(),encoded));drops.put(key,drop);
        }
        if(drop!=null&&drop.state().equals("PREPARED"))spawnDrops(drop);
        clear(block);block.setBlockData(Bukkit.createBlockData(saved.before()),false);BlockEntitySnapshot.restore(block.getState(),saved.beforeNbt());
    }
    private void spawnDrops(WarCampDropsStore.Drop drop) {
        var world=world(drop.world());var existing=new HashSet<String>();
        for(var entity:world.getChunkAt(Math.floorDiv(drop.x(),16),Math.floorDiv(drop.z(),16)).getEntities())if(entity instanceof Item){var marker=entity.getPersistentDataContainer().get(dropKey,PersistentDataType.STRING);if(marker!=null)existing.add(marker);}
        var items=ItemStack.deserializeItemsFromBytes(drop.items());
        for(int slot=0;slot<items.length;slot++) {
            var stack=items[slot];if(stack==null||stack.getType().isAir())continue;String marker=drop.id()+":"+slot;if(existing.contains(marker))continue;
            var entity=world.dropItem(new Location(world,drop.x()+.5,drop.y()+.5,drop.z()+.5),stack.clone(),item->{item.getPersistentDataContainer().set(dropKey,PersistentDataType.STRING,marker);item.setPickupDelay(32767);item.setGravity(false);item.setUnlimitedLifetime(true);item.setInvulnerable(true);item.setVelocity(new org.bukkit.util.Vector());});
            if(!entity.isValid())throw new IllegalStateException("Prepared camp item drop was rejected");
        }
    }
    private void unlockDrops()throws SQLException {
        var done=storage.query(WarCampDropsStore::unreleasedCamps);var worlds=new HashSet<String>();
        for(long camp:done)for(var drop:storage.query(db->WarCampDropsStore.list(db,camp))) {
            if(!drop.state().equals("DONE")||drop.released())continue;var world=world(drop.world());worlds.add(drop.world());
            for(var entity:world.getChunkAt(Math.floorDiv(drop.x(),16),Math.floorDiv(drop.z(),16)).getEntities())if(entity instanceof Item item){String marker=item.getPersistentDataContainer().get(dropKey,PersistentDataType.STRING);if(marker!=null&&marker.startsWith(drop.id()+":")){item.setPickupDelay(0);item.setGravity(true);item.setUnlimitedLifetime(false);item.setTicksLived(1);item.setInvulnerable(false);}}
        }
        for(var name:worlds)world(name).save(true);storage.transaction(db->{for(long camp:done)WarCampDropsStore.release(db,camp);return null;});
    }
    private void reconcileControls(long now)throws SQLException {
        record Visible(String world,int x,int y,int z,int health) {}
        var points=storage.query(db->{var out=new ArrayList<Visible>();try(var s=db.prepareStatement("SELECT p.world,p.x,p.y,p.z,p.hitpoints FROM war_camp_points p JOIN war_camps c ON c.id=p.camp_id JOIN war_sessions w ON w.start_at=c.war_start WHERE p.kind='control' AND c.state='ACTIVE' AND w.state='ACTIVE' AND w.start_at<=? AND w.end_at>?")){s.setLong(1,now);s.setLong(2,now);try(var r=s.executeQuery()){while(r.next())out.add(new Visible(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4),r.getInt(5)));}}return out;});
        for(var point:points){var block=world(point.world()).getBlockAt(point.x(),point.y(),point.z());var expected=point.health()>0?Material.OBSIDIAN:Material.AIR;if(block.getType()!=expected)block.setType(expected,false);}
    }
    private void interruption(String phase,long camp) {
        if(camp==Long.getLong("civcraft.test.warcamp.id",-1)&&phase.equals(System.getProperty("civcraft.test.warcamp.interruption"))){paused=true;throw new IllegalStateException("Injected war camp interruption at "+phase);}
    }
    private record Cell(long camp,String state,String after,boolean respawn) {}
    private Cell cell(Location location)throws SQLException {
        return storage.query(db->{try(var s=db.prepareStatement("SELECT c.id,c.state,b.after_data,EXISTS(SELECT 1 FROM war_camp_points p WHERE p.camp_id=c.id AND p.kind='respawn' AND p.world=b.world AND p.x=b.x AND p.z=b.z AND b.y BETWEEN p.y AND p.y+1) FROM war_camp_blocks b JOIN war_camps c ON c.id=b.camp_id WHERE b.world=? AND b.x=? AND b.y=? AND b.z=? AND c.state IN ('PREPARED','ACTIVE','RESTORING') ORDER BY c.id DESC LIMIT 1")){s.setString(1,location.getWorld().getName());s.setInt(2,location.getBlockX());s.setInt(3,location.getBlockY());s.setInt(4,location.getBlockZ());try(var r=s.executeQuery()){return r.next()?new Cell(r.getLong(1),r.getString(2),r.getString(3),r.getBoolean(4)):null;}}});
    }
    private boolean locked(Location location) {
        if(location==null)return false;try{var cell=cell(location);return cell!=null&&!cell.state().equals("ACTIVE");}catch(SQLException failure){return true;}
    }
    private boolean protectedBlock(Location location) {
        try{var cell=cell(location);return cell!=null&&(!cell.state().equals("ACTIVE")||cell.respawn()||!Bukkit.createBlockData(cell.after()).getMaterial().isAir());}catch(SQLException failure){return true;}
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void breakBlock(BlockBreakEvent event) {
        try{var cell=cell(event.getBlock().getLocation());if(cell==null)return;if(!cell.state().equals("ACTIVE")){event.setCancelled(true);return;}if(Bukkit.createBlockData(cell.after()).getMaterial().isAir()&&!cell.respawn())return;
            event.setCancelled(true);var p=event.getBlock();var hit=storage.transaction(db->WarCampStore.hit(db,UUID.randomUUID().toString(),event.getPlayer().getUniqueId(),cell.camp(),p.getWorld().getName(),p.getX(),p.getY(),p.getZ(),1,System.currentTimeMillis()));if(hit.health()==0)p.setType(Material.AIR,false);event.getPlayer().sendMessage(Component.text(hit.destroyed()?"Военный лагерь уничтожен.":"Контрольный блок лагеря: "+hit.health()+" HP."));
        }catch(IllegalArgumentException failure){event.setCancelled(true);event.getPlayer().sendMessage(Component.text(failure.getMessage()));}catch(SQLException failure){event.setCancelled(true);plugin.getLogger().severe("War camp damage failed: "+failure.getMessage());}
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void placeBlock(BlockPlaceEvent event) {
        try{if(protectedBlock(event.getBlock().getLocation()))event.setCancelled(true);}catch(RuntimeException failure){event.setCancelled(true);}
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void open(InventoryOpenEvent event){if(locked(event.getInventory().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void move(InventoryMoveItemEvent event){if(locked(event.getSource().getLocation())||locked(event.getDestination().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void physics(BlockPhysicsEvent event){if(protectedBlock(event.getBlock().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void fluids(BlockFromToEvent event){if(locked(event.getBlock().getLocation())||protectedBlock(event.getToBlock().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void explode(EntityExplodeEvent event){event.blockList().removeIf(b->protectedBlock(b.getLocation()));}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void explodeBlock(BlockExplodeEvent event){event.blockList().removeIf(b->protectedBlock(b.getLocation()));}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void burn(BlockBurnEvent event){if(protectedBlock(event.getBlock().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void change(EntityChangeBlockEvent event){if(protectedBlock(event.getBlock().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void piston(BlockPistonExtendEvent event){if(protectedBlock(event.getBlock().getLocation())||event.getBlocks().stream().anyMatch(b->protectedBlock(b.getLocation())||protectedBlock(b.getRelative(event.getDirection()).getLocation())))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void retract(BlockPistonRetractEvent event){if(protectedBlock(event.getBlock().getLocation())||event.getBlocks().stream().anyMatch(b->protectedBlock(b.getLocation())||protectedBlock(b.getRelative(event.getDirection()).getLocation())))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void pickup(EntityPickupItemEvent event){var marker=event.getItem().getPersistentDataContainer().get(dropKey,PersistentDataType.STRING);if(marker==null)return;try{boolean prepared=storage.query(db->{try(var s=db.prepareStatement("SELECT 1 FROM war_camp_drops WHERE id=? AND state='PREPARED'")){s.setString(1,marker.substring(0,marker.lastIndexOf(':')));try(var r=s.executeQuery()){return r.next();}}});if(prepared)event.setCancelled(true);}catch(SQLException failure){event.setCancelled(true);}}
}
