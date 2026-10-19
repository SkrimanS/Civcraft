package com.avrgaming.civcraft.modern.camp;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.town.ConsumptionContainers.Position;
import java.sql.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Firework;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;

/** Apply one frozen ruin plan, save the world, then release ownership/protection atomically. */
public final class CampRuinService implements Listener {
    @FunctionalInterface public interface BeforeDelete {void accept(long camp)throws SQLException;}
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final BeforeDelete beforeDelete;
    private final CampTemplateService templates;
    private final String structureId;
    private final Set<Position> locked=new HashSet<>();
    private final Set<Long> ruining=new HashSet<>();
    private final Random random=new Random();
    private final NamespacedKey effectKey;
    private boolean paused;
    private long lastWarning;
    public CampRuinService(CivCraftModernPlugin plugin,String structureId,CampTemplateService templates,BeforeDelete beforeDelete)throws SQLException {
        this.plugin=plugin;storage=plugin.storage();this.structureId=structureId;this.templates=templates;this.beforeDelete=beforeDelete;effectKey=new NamespacedKey(plugin,"camp-ruin-effect");
        storage.transaction(db->{CampRuinStore.install(db);return null;});refreshLocks();
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,1L,20L);
    }
    public boolean ruining(long camp){return ruining.contains(camp);}
    public void request(long camp,boolean title)throws SQLException {
        if(paused)return;
        for(var existing:storage.query(CampRuinStore::pending))if(existing.camp()==camp){finish(existing);return;}
        var build=storage.query(db->{CampLonghouseStore.requireUnlocked(db,camp);try(var s=db.prepareStatement("""
                SELECT c.owner_uuid,b.id FROM camps c JOIN structure_builds b ON b.world=c.world AND b.x=c.x AND b.y=c.y AND b.z=c.z
                WHERE c.id=? AND b.structure_id=? AND b.status='COMPLETE' ORDER BY b.id DESC LIMIT 1
                """)) {s.setLong(1,camp);s.setString(2,structureId);try(var r=s.executeQuery()){return r.next()?new Build(r.getLong(2),UUID.fromString(r.getString(1))):null;}}});
        if(build==null)throw new SQLException("Completed camp structure is unavailable for ruin planning");
        var positions=storage.query(db->{var out=new LinkedHashSet<Position>();try(var s=db.prepareStatement("SELECT world,x,y,z FROM protected_blocks WHERE build_id=? ORDER BY world,x,y,z")){s.setLong(1,build.id());try(var r=s.executeQuery()){while(r.next())out.add(new Position(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4)));}}
            try(var s=db.prepareStatement("SELECT world,x,y,z,command FROM camp_template_markers WHERE camp_id=? AND command IN ('/growth','/fire')")){s.setLong(1,camp);try(var r=s.executeQuery()){while(r.next()){var p=new Position(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4));if(r.getString(5).equals("/fire"))out.remove(p);else{out.add(p);out.add(new Position(p.world(),p.x(),p.y()+1,p.z()));}}}}return out;});
        var special=storage.query(db->{var out=new HashSet<Position>();try(var s=db.prepareStatement("SELECT world,x,y,z FROM structure_chests WHERE build_id=?")){s.setLong(1,build.id());try(var r=s.executeQuery()){while(r.next())out.add(new Position(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4)));}}return out;});
        var cells=new ArrayList<CampRuinStore.Cell>();
        for(var p:positions) {
            var block=block(p);var result=CampRuinRules.roll(block.getType(),special.contains(p) || block.getState() instanceof Sign,random);
            cells.add(new CampRuinStore.Cell(p,block.getBlockData().getAsString(),result.replacement()==null?null:result.replacement().createBlockData().getAsString(),result.effect()));
        }
        var members=storage.query(db->{var out=new ArrayList<UUID>();try(var s=db.prepareStatement("SELECT uuid FROM camp_members WHERE camp_id=?")){s.setLong(1,camp);try(var r=s.executeQuery()){while(r.next())out.add(UUID.fromString(r.getString(1)));}}return out;});
        var pending=new CampRuinStore.Pending(camp,build.id(),build.leader(),title,cells,members);
        storage.transaction(db->{CampRuinStore.prepare(db,pending);return null;});lock(pending);interrupt("prepared",camp);finish(pending);
    }
    private void tick() {
        if(paused)return;
        try {
            refreshLocks();
            for(var p:storage.query(CampRuinStore::pending)){if(paused)break;try{finish(p);}catch(Exception failure){warn(failure);}}
            if(paused)return;
            var expired=storage.query(db->{var out=new ArrayList<Long>();try(var s=db.createStatement();var r=s.executeQuery("SELECT id FROM camps WHERE firepoints_hours<0 OR control_hp<=0 ORDER BY id")){while(r.next())out.add(r.getLong(1));}return out;});
            for(long camp:expired){if(paused)break;try{request(camp,true);}catch(Exception failure){warn(failure);}}
        }catch(Exception failure){warn(failure);}
    }
    private void refreshLocks()throws SQLException {locked.clear();ruining.clear();for(var p:storage.query(CampRuinStore::pending))lock(p);}
    private void lock(CampRuinStore.Pending p){ruining.add(p.camp());for(var cell:p.cells())locked.add(cell.position());}
    private void finish(CampRuinStore.Pending p)throws SQLException {
        boolean pending=storage.query(db->{try(var s=db.prepareStatement("SELECT 1 FROM camp_ruins WHERE camp_id=? AND status='PREPARED'")){s.setLong(1,p.camp());try(var r=s.executeQuery()){return r.next();}}});
        if(!pending)return;
        var worlds=new HashSet<World>();
        // Resolve all chunks before mutation; a missing world must not leave a newly half-applied plan.
        for(var cell:p.cells())worlds.add(block(cell.position()).getWorld());
        for(var cell:p.cells())if(cell.after()!=null) {
            var block=block(cell.position());var after=Bukkit.createBlockData(cell.after());
            if(block.getBlockData().matches(after))continue;
            var before=Bukkit.createBlockData(cell.before());
            if(block.getState() instanceof Container && block.getType()!=before.getMaterial())throw new IllegalStateException("Unexpected container in pending camp ruin: "+cell.position());
            block.setBlockData(after,false);
        }
        for(var world:worlds)world.save(true);
        interrupt("world-saved",p.camp());beforeDelete.accept(p.camp());
        for(var world:worlds)world.save(true);
        boolean completed=storage.transaction(db->CampRuinStore.complete(db,p));
        for(var cell:p.cells())locked.remove(cell.position());ruining.remove(p.camp());
        if(completed) {
            templates.refresh();
            if(p.title())for(var uuid:p.members()){var player=Bukkit.getPlayer(uuid);if(player!=null)player.showTitle(Title.title(Component.text("Лагерь разрушен"),Component.empty()));}
            for(var cell:p.cells())if(cell.effect())effect(cell.position());
        }
    }
    private static Block block(Position p){var world=Bukkit.getWorld(p.world());if(world==null)throw new IllegalStateException("Camp ruin world is unavailable: "+p.world());world.getChunkAt(p.x()>>4,p.z()>>4).load();return world.getBlockAt(p.x(),p.y(),p.z());}
    private void effect(Position p) {
        var world=Bukkit.getWorld(p.world());if(world==null)return;
        for(int i=0;i<3;i++)try{var firework=world.spawn(new Location(world,p.x()+0.5,p.y()+0.5,p.z()+0.5),Firework.class);firework.getPersistentDataContainer().set(effectKey,PersistentDataType.BYTE,(byte)1);var meta=firework.getFireworkMeta();meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BURST).withColor(Color.ORANGE,Color.RED).withTrail().withFlicker().build());meta.setPower(0);firework.setFireworkMeta(meta);firework.detonate();}catch(RuntimeException failure){warn(failure);}
    }
    private void interrupt(String phase,long camp){if(camp==Long.getLong("civcraft.test.camp.ruin.camp",-1) && phase.equals(System.getProperty("civcraft.test.camp.ruin.interruption"))){paused=true;throw new IllegalStateException("Injected camp ruin interruption at "+phase);}}
    private void warn(Exception failure){long now=System.currentTimeMillis();if(now-lastWarning>30000){lastWarning=now;plugin.getLogger().warning("Camp ruin: "+failure.getMessage());}}
    private static Position at(Block b){return new Position(b.getWorld().getName(),b.getX(),b.getY(),b.getZ());}
    private boolean locked(Block b){return locked.contains(at(b));}
    private boolean locked(Inventory i){return locked(i.getHolder());}
    private boolean locked(InventoryHolder h){return h instanceof BlockState b?locked(b.getBlock()):h instanceof DoubleChest d && (locked(d.getLeftSide()) || locked(d.getRightSide()));}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void open(InventoryOpenEvent e){if(locked(e.getInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void click(InventoryClickEvent e){if(locked(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drag(InventoryDragEvent e){if(locked(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void move(InventoryMoveItemEvent e){if(locked(e.getSource()) || locked(e.getDestination()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void pickup(InventoryPickupItemEvent e){if(locked(e.getInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void burnFuel(FurnaceBurnEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void smelt(FurnaceSmeltEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void place(BlockPlaceEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void physics(BlockPhysicsEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void fertilize(BlockFertilizeEvent e){if(e.getBlocks().stream().anyMatch(b->locked(b.getBlock())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void tree(StructureGrowEvent e){if(e.getBlocks().stream().anyMatch(b->locked(b.getBlock())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interact(org.bukkit.event.player.PlayerInteractEvent e){if(e.getClickedBlock()!=null && locked(e.getClickedBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void empty(org.bukkit.event.player.PlayerBucketEmptyEvent e){if(locked(e.getBlock()) || locked(e.getBlockClicked().getRelative(e.getBlockFace())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void fill(org.bukkit.event.player.PlayerBucketFillEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void fade(BlockFadeEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void grow(BlockGrowEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void spread(BlockSpreadEvent e){if(locked(e.getBlock()) || locked(e.getSource()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void burn(BlockBurnEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void flow(BlockFromToEvent e){if(locked(e.getBlock()) || locked(e.getToBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void entityChange(EntityChangeBlockEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(EntityExplodeEvent e){e.blockList().removeIf(this::locked);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(BlockExplodeEvent e){e.blockList().removeIf(this::locked);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void piston(BlockPistonExtendEvent e){if(e.getBlocks().stream().anyMatch(b->locked(b) || locked(b.getRelative(e.getDirection()))))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void piston(BlockPistonRetractEvent e){if(e.getBlocks().stream().anyMatch(b->locked(b) || locked(b.getRelative(e.getDirection()))))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void effectDamage(EntityDamageByEntityEvent e){if(e.getDamager() instanceof Firework f && f.getPersistentDataContainer().has(effectKey,PersistentDataType.BYTE))e.setCancelled(true);}
    private record Build(long id,UUID leader) {}
}
