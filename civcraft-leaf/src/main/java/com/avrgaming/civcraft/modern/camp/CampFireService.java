package com.avrgaming.civcraft.modern.camp;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.town.ConsumptionContainers.Position;
import com.avrgaming.civcraft.modern.trade.GoodieChestStore;
import java.io.*;
import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

/** Original hourly camp fuel. Negative reserve is durable input to camp ruin processing. */
public final class CampFireService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final CampTemplateService templates;
    private final int maximum,coal;
    private final Set<Position> locked=new HashSet<>(),firepits=new HashSet<>(),initialized=new HashSet<>();
    private final Set<Position> dirtyVisual=new HashSet<>();
    private final NamespacedKey applied;
    private boolean paused;
    private long lastWarning;
    public CampFireService(CivCraftModernPlugin plugin,CampTemplateService templates)throws SQLException,IOException {
        this.plugin=plugin;this.storage=plugin.storage();this.templates=templates;applied=new NamespacedKey(plugin,"last-camp-fuel");
        var config=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/camp.yml"));
        maximum=config.getInt("camp.firepoints",24);coal=config.getInt("camp.coal_per_firepoint",4);
        if(maximum<1 || coal<1)throw new IllegalArgumentException("Неверные настройки топлива лагеря");
        storage.transaction(db->{CampFireStore.install(db);return null;});refreshLocks();
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,1L,20L);
    }
    public int maximum(){return maximum;}
    public int coalPerPoint(){return coal;}
    public int points(long camp)throws SQLException {return storage.query(db->{try(var s=db.prepareStatement("SELECT firepoints_hours FROM camps WHERE id=?")){s.setLong(1,camp);try(var r=s.executeQuery()){return r.next()?r.getInt(1):0;}}});}
    /** Only camps whose fire step actually finished may run the same hour's longhouse step. */
    public Set<Long> runHour() {
        var ready=new HashSet<Long>();if(paused)return ready;
        try {
            recover();if(paused)return ready;
            var ids=storage.query(db->{var out=new ArrayList<Long>();try(var s=db.createStatement();var r=s.executeQuery("SELECT c.id FROM camps c JOIN camp_template_index i ON i.camp_id=c.id JOIN structure_builds b ON b.id=i.build_id WHERE c.firepoints_hours>=0 AND b.status='COMPLETE' ORDER BY c.id")){while(r.next())out.add(r.getLong(1));}return out;});
            for(long id:ids){if(paused)break;try{if(process(id))ready.add(id);}catch(Exception failure){warn(failure);}}
            reconcileFire();
        }catch(Exception failure){warn(failure);}
        return Set.copyOf(ready);
    }
    private boolean process(long id)throws SQLException,IOException {
        var state=storage.query(db->{CampLonghouseStore.requireUnlocked(db,id);try(var s=db.prepareStatement("SELECT owner_uuid,firepoints_hours FROM camps WHERE id=?")){s.setLong(1,id);try(var r=s.executeQuery()){return r.next()?new State(UUID.fromString(r.getString(1)),r.getInt(2)):null;}}});
        if(state==null || state.points()<0)return false;
        var positions=storage.query(db->{var out=new LinkedHashSet<Position>();try(var s=db.prepareStatement("SELECT world,x,y,z FROM camp_template_markers WHERE camp_id=? AND command='/firefurnace' ORDER BY world,x,y,z")){s.setLong(1,id);try(var r=s.executeQuery()){while(r.next())out.add(new Position(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4)));}}return out;});
        if(positions.isEmpty())throw new IllegalStateException("Camp has no indexed fire furnaces: "+id);
        for(var p:positions)if(!load(p))return false;
        storage.query(db->{GoodieChestStore.requirePositionsAvailable(db,positions.stream().map(p->new GoodieChestStore.Position(p.world(),p.x(),p.y(),p.z())).toList());return null;});
        var before=CampFireContainers.capture(positions);var consumed=CampFireContainers.consume(before,coal);
        boolean fed=consumed.isPresent();int after=fed?(int)Math.min(maximum,(long)state.points()+1):state.points()-1;
        var pending=new CampFireStore.Pending(UUID.randomUUID().toString(),id,state.leader(),state.points(),after,fed,CampFireContainers.encode(consumed.orElse(before)));
        storage.transaction(db->{CampFireStore.prepare(db,pending);return null;});for(var p:positions)locked.add(p);
        interrupt("prepared",id);return finish(pending) && after>=0;
    }
    private void tick(){if(paused)return;try{recover();if(!paused)reconcileFire();}catch(Exception failure){warn(failure);}}
    private void refreshLocks()throws SQLException,IOException {
        var cells=new HashSet<Position>();for(var pending:storage.query(CampFireStore::pending))for(var image:CampFireContainers.decode(pending.containers()))cells.add(image.position());locked.clear();locked.addAll(cells);
    }
    private void recover()throws SQLException,IOException {
        refreshLocks();for(var p:storage.query(CampFireStore::pending)){if(paused)break;try{finish(p);}catch(Exception failure){warn(failure);}}
    }
    private boolean finish(CampFireStore.Pending p)throws SQLException,IOException {
        var images=CampFireContainers.decode(p.containers());for(var image:images)if(!load(image.position()))return false;
        if(p.fed()){for(var world:CampFireContainers.apply(images,p.id(),applied))world.save(true);interrupt("world-saved",p.camp());}
        boolean completed=storage.transaction(db->CampFireStore.complete(db,p));for(var image:images)locked.remove(image.position());
        if(completed && !p.fed()) {
            var members=storage.query(db->{var out=new ArrayList<UUID>();try(var s=db.prepareStatement("SELECT uuid FROM camp_members WHERE camp_id=?")){s.setLong(1,p.camp());try(var r=s.executeQuery()){while(r.next())out.add(UUID.fromString(r.getString(1)));}}return out;});
            for(var uuid:members){var player=Bukkit.getPlayer(uuid);if(player!=null){player.sendMessage(p.after()<0?"§cКостёр лагеря погас.":"§eНе хватает угля. Запас костра: "+p.after()+" ч.");if(p.after()>=0 && (double)p.after()/maximum<0.3)player.sendMessage("§cВ костре осталось меньше 30% запаса. Положите уголь в печи!");}}
        }return completed;
    }
    private void reconcileFire()throws SQLException {
        var cells=storage.query(db->{var out=new ArrayList<Flame>();try(var s=db.createStatement();var r=s.executeQuery("SELECT m.*,c.firepoints_hours FROM camp_template_markers m JOIN camps c ON c.id=m.camp_id JOIN camp_template_index i ON i.camp_id=c.id JOIN structure_builds b ON b.id=i.build_id WHERE m.command IN ('/firepit','/fire') AND b.status='COMPLETE' AND NOT EXISTS(SELECT 1 FROM camp_ruins ruin WHERE ruin.camp_id=c.id AND ruin.status='PREPARED') ORDER BY m.camp_id,m.world,m.x,m.y,m.z")) {
            while(r.next())out.add(new Flame(r.getLong("camp_id"),new Position(r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z")),r.getString("command"),r.getString("parameter"),r.getInt("firepoints_hours")));
        }return out;});
        firepits.clear();var byCamp=new HashMap<Long,List<Flame>>();var present=new HashSet<Position>();
        for(var cell:cells){present.add(cell.position());if(cell.command().equals("/firepit")){firepits.add(cell.position());byCamp.computeIfAbsent(cell.camp(),k->new ArrayList<>()).add(cell);}}
        initialized.retainAll(present);dirtyVisual.retainAll(present);var desired=new HashMap<Position,Boolean>();
        for(var group:byCamp.values()) {
            var ids=new HashSet<Integer>();int lit=(int)((double)group.getFirst().points()/maximum*group.size());
            for(var cell:group){String param=cell.parameter().trim();if(!param.toLowerCase(Locale.ROOT).startsWith("id:"))throw new SQLException("Missing firepit ID");int index=Integer.parseInt(param.substring(3).trim());if(index<0 || index>=group.size() || !ids.add(index))throw new SQLException("Invalid firepit ID");desired.put(cell.position(),index<lit);}
        }
        var worlds=new HashSet<World>();
        for(var cell:cells) {
            var p=cell.position();var world=Bukkit.getWorld(p.world());if(world==null || !world.isChunkLoaded(p.x()>>4,p.z()>>4))continue;
            boolean pit=cell.command().equals("/firepit");if(!pit && initialized.contains(p))continue;
            var block=world.getBlockAt(p.x(),p.y(),p.z());var target=!pit || desired.getOrDefault(p,false)?Material.FIRE:Material.AIR;
            if(block.getState() instanceof Container)throw new IllegalStateException("Container occupies camp firepit: "+p);
            if(block.getType()!=target || !initialized.contains(p) || dirtyVisual.contains(p)){dirtyVisual.add(p);if(block.getType()!=target)block.setType(target,false);if(pit)templates.protect(cell.camp(),block);worlds.add(world);}
        }
        for(var world:worlds)world.save(true);
        dirtyVisual.removeIf(p->worlds.contains(Bukkit.getWorld(p.world())));
        for(var cell:cells){var p=cell.position();var world=Bukkit.getWorld(p.world());if(world!=null && world.isChunkLoaded(p.x()>>4,p.z()>>4))initialized.add(p);}
    }
    private static boolean load(Position p){var world=Bukkit.getWorld(p.world());if(world==null)return false;return world.getChunkAt(p.x()>>4,p.z()>>4).load();}
    private void interrupt(String phase,long camp){if(camp==Long.getLong("civcraft.test.camp.fire.camp",-1) && phase.equals(System.getProperty("civcraft.test.camp.fire.interruption"))){paused=true;throw new IllegalStateException("Injected camp fire interruption at "+phase);}}
    private void warn(Exception failure){long now=System.currentTimeMillis();if(now-lastWarning>30000){lastWarning=now;plugin.getLogger().warning("Camp fire: "+failure.getMessage());}}
    private static Position at(Block b){return new Position(b.getWorld().getName(),b.getX(),b.getY(),b.getZ());}
    private boolean locked(Block b){return locked.contains(at(b));}
    private boolean locked(Inventory i){return i.getHolder() instanceof Furnace f && locked(f.getBlock());}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void open(InventoryOpenEvent e){if(locked(e.getInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void click(InventoryClickEvent e){if(locked(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drag(InventoryDragEvent e){if(locked(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void move(InventoryMoveItemEvent e){if(locked(e.getSource()) || locked(e.getDestination()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void burnFuel(FurnaceBurnEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void smelt(FurnaceSmeltEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void fade(BlockFadeEvent e){if(locked(e.getBlock()) || firepits.contains(at(e.getBlock())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void burn(BlockBurnEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void place(BlockPlaceEvent e){if(locked(e.getBlock()) || firepits.contains(at(e.getBlock())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void flow(BlockFromToEvent e){if(locked(e.getToBlock()) || firepits.contains(at(e.getToBlock())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void entityChange(org.bukkit.event.entity.EntityChangeBlockEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(BlockExplodeEvent e){e.blockList().removeIf(this::locked);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(EntityExplodeEvent e){e.blockList().removeIf(this::locked);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void piston(BlockPistonExtendEvent e){if(e.getBlocks().stream().anyMatch(this::locked))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void piston(BlockPistonRetractEvent e){if(e.getBlocks().stream().anyMatch(this::locked))e.setCancelled(true);}
    private record State(UUID leader,int points) {}
    private record Flame(long camp,Position position,String command,String parameter,int points) {}
}
