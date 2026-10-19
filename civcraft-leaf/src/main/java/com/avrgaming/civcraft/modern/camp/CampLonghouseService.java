package com.avrgaming.civcraft.modern.camp;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.items.LegacyItemMaterials;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.town.*;
import com.avrgaming.civcraft.modern.trade.GoodieChestStore;
import java.io.*;
import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Original longhouse and sifter operations on physical camp chests. */
public final class CampLonghouseService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final Map<Integer,ConsumptionCatalog.Level> levels;
    private final Map<Integer,Material> materials=new HashMap<>();
    private final Set<ConsumptionContainers.Position> locked=new HashSet<>();
    private final NamespacedKey applied;
    private final CampSifterRules sifter;
    private final Random random=new Random();
    private boolean paused;
    private long lastWarning;
    public CampLonghouseService(CivCraftModernPlugin plugin)throws SQLException,IOException {
        this.plugin=plugin;storage=plugin.storage();applied=new NamespacedKey(plugin,"last-camp-meal");
        levels=ConsumptionCatalog.longhouse(new File(plugin.getDataFolder(),"civcraft/data/camp.yml"));
        var config=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/camp.yml"));
        sifter=new CampSifterRules(config.getDouble("camp.sifter_gold_nugget_chance",0.10),config.getDouble("camp.sifter_iron_ingot_chance",0.025));
        var registry=new LegacyItemMaterials(plugin.getResource("legacy-materials.properties"));
        for(var level:levels.values())for(int id:level.food().keySet())materials.putIfAbsent(id,registry.resolve(id,0));
        storage.transaction(db->{CampLonghouseStore.install(db,Collections.max(levels.keySet()));return null;});
        refreshLocks();
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::recover,1L,20L);
    }
    public int maximum(int level){return levels.containsKey(level)?levels.get(level).count():0;}
    public int bread(int level){return levels.containsKey(level)?levels.get(level).food().getOrDefault(297,0):0;}
    public void runSifters() {
        if(paused)return;
        try {
            var ids=storage.query(db->{var out=new ArrayList<Long>();try(var s=db.createStatement();var r=s.executeQuery("SELECT id FROM camps WHERE breaker_upgrade=1 AND firepoints_hours>=0 AND "+com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.visible(db,"camps","camps.id")+" ORDER BY id")){while(r.next())out.add(r.getLong(1));}return out;});
            for(long id:ids){if(paused)break;try{sift(id);}catch(Exception failure){warn(failure);}}
        }catch(Exception failure){warn(failure);}
    }
    private void sift(long id)throws SQLException,IOException {
        var camp=storage.query(db->{CampLonghouseStore.requireUnlocked(db,id);try(var s=db.prepareStatement("SELECT * FROM camps WHERE id=? AND breaker_upgrade=1 AND firepoints_hours>=0")) {
            s.setLong(1,id);try(var r=s.executeQuery()){return r.next()?new Sifter(UUID.fromString(r.getString("owner_uuid")),r.getInt("level"),r.getInt("experience"),point(r,"breaker_input"),point(r,"breaker_output")):null;}
        }});
        if(camp==null || camp.input()==null || camp.output()==null)return;
        var source=positions(camp.input());var destination=positions(camp.output());
        if(source.isEmpty() || destination.isEmpty())return;
        var all=new LinkedHashSet<>(source);all.addAll(destination);
        storage.query(db->{GoodieChestStore.requirePositionsAvailable(db,all.stream().map(p->new GoodieChestStore.Position(p.world(),p.x(),p.y(),p.z())).toList());return null;});
        var plan=sifter.plan(ConsumptionContainers.capture(source),ConsumptionContainers.capture(destination),random);
        if(plan.isEmpty())return;
        var meal=new CampLonghouseStore.Pending(UUID.randomUUID().toString(),id,camp.leader(),camp.level(),camp.count(),0,"SIFT",ConsumptionContainers.encode(plan.get()));
        storage.transaction(db->{CampLonghouseStore.prepare(db,meal,camp.level(),camp.count());return null;});
        for(var image:plan.get())locked.add(image.position());
        interrupt("sifter-prepared",id);finish(meal);
    }
    private static ConsumptionContainers.Position point(ResultSet r,String prefix)throws SQLException {
        String world=r.getString(prefix+"_world");return world==null?null:new ConsumptionContainers.Position(world,r.getInt(prefix+"_x"),r.getInt(prefix+"_y"),r.getInt(prefix+"_z"));
    }
    private static Set<ConsumptionContainers.Position> positions(ConsumptionContainers.Position point) {
        var world=Bukkit.getWorld(point.world());if(world==null || !world.isChunkLoaded(point.x()>>4,point.z()>>4))return Set.of();
        if(!(world.getBlockAt(point.x(),point.y(),point.z()).getState() instanceof Chest chest))return Set.of();
        var out=new LinkedHashSet<ConsumptionContainers.Position>();addPositions(chest.getInventory().getHolder(),out);
        for(var p:out)if(!world.isChunkLoaded(p.x()>>4,p.z()>>4))return Set.of();
        return Collections.unmodifiableSet(out);
    }
    public void runHour() { recover(); }
    private void produce(long id,String fireOperation)throws SQLException,IOException {
        var camp=storage.query(db->{CampLonghouseStore.requireUnlocked(db,id,true);try(var s=db.prepareStatement("SELECT owner_uuid,level,experience,food_chest_world,food_chest_x,food_chest_y,food_chest_z FROM camps WHERE id=? AND longhouse_upgrade=1")) {
            s.setLong(1,id);try(var r=s.executeQuery()){return r.next()?new Camp(UUID.fromString(r.getString(1)),r.getInt(2),r.getInt(3),r.getString(4),r.getInt(5),r.getInt(6),r.getInt(7)):null;}
        }});
        if(camp==null || camp.world()==null){skipMeal(fireOperation);return;}
        var world=Bukkit.getWorld(camp.world());if(world==null)return;
        world.getChunkAt(camp.x()>>4,camp.z()>>4).load();
        if(!(world.getBlockAt(camp.x(),camp.y(),camp.z()).getState() instanceof Chest chest)){skipMeal(fireOperation);return;}
        var positions=new LinkedHashSet<ConsumptionContainers.Position>();
        addPositions(chest.getInventory().getHolder(),positions);
        if(positions.isEmpty())return;
        for(var p:positions)Bukkit.getWorld(p.world()).getChunkAt(p.x()>>4,p.z()>>4).load();
        storage.query(db->{GoodieChestStore.requirePositionsAvailable(db,positions.stream().map(p->new GoodieChestStore.Position(p.world(),p.x(),p.y(),p.z())).toList());return null;});
        var before=ConsumptionContainers.capture(positions);
        var counts=new HashMap<>(ConsumptionContainers.counts(before,materials));
        var current=levels.get(camp.level());if(current==null)throw new SQLException("Invalid longhouse level "+camp.level());
        boolean enough=enough(counts,current.food());
        if(enough)for(var food:current.food().entrySet())counts.merge(food.getKey(),-(long)food.getValue(),Long::sum);
        var next=levels.get(camp.level()+1);var previous=levels.get(camp.level()-1);
        // Original component checks the CURRENT level's food a second time, without consuming it.
        var state=ConsumptionRules.advance(camp.level(),camp.count(),current.count(),next==null?null:next.count(),previous==null?null:previous.count(),enough,enough(counts,current.food()));
        var after=enough?ConsumptionContainers.after(before,counts,materials):before;
        if(enough) {
            var token=plugin.nativeItems().tagged(plugin.nativeItems().create("mat_token_of_leadership",1),camp.leader().toString());
            after=ConsumptionContainers.addBestEffort(after,token);
        }
        var meal=new CampLonghouseStore.Pending(UUID.randomUUID().toString(),id,camp.leader(),state.level(),state.count(),enough?current.output():0,state.result().name(),ConsumptionContainers.encode(after),fireOperation);
        storage.transaction(db->{CampLonghouseStore.prepare(db,meal,camp.level(),camp.count());return null;});
        for(var image:after)locked.add(image.position());
        interrupt("prepared",id);
        finish(meal);
    }
    private static boolean enough(Map<Integer,Long> counts,Map<Integer,Integer> food) {
        return food.entrySet().stream().allMatch(e->counts.getOrDefault(e.getKey(),0L)>=e.getValue());
    }
    private static void addPositions(InventoryHolder holder,Set<ConsumptionContainers.Position> result) {
        if(holder instanceof Chest c)result.add(new ConsumptionContainers.Position(c.getWorld().getName(),c.getX(),c.getY(),c.getZ()));
        else if(holder instanceof DoubleChest c){addPositions(c.getLeftSide(),result);addPositions(c.getRightSide(),result);}
    }
    private void recover() {
        if(paused)return;
        try {
            refreshLocks();
            for(var meal:storage.query(CampLonghouseStore::pending)){if(paused)break;try{finish(meal);}catch(Exception failure){warn(failure);}}
            if(!paused)processDueMeals();
        }catch(Exception failure){warn(failure);}
    }
    private void processDueMeals()throws SQLException {
        var due=storage.query(db->{var out=new ArrayList<Due>();try(var s=db.createStatement();var r=s.executeQuery("SELECT id,camp_id FROM camp_fire_operations WHERE status='COMPLETED' AND meal_due=1 AND "+com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.visible(db,"camps","camp_fire_operations.camp_id")+" ORDER BY created_at,id")){while(r.next())out.add(new Due(r.getString(1),r.getLong(2)));}return out;});
        for(var parent:due){if(paused)break;try{produce(parent.camp(),parent.id());}catch(Exception failure){warn(failure);}}
    }
    private void skipMeal(String fireOperation)throws SQLException {
        storage.transaction(db->{try(var s=db.prepareStatement("UPDATE camp_fire_operations SET meal_due=0 WHERE id=? AND status='COMPLETED' AND NOT EXISTS(SELECT 1 FROM camp_longhouse_operations child WHERE child.fire_operation=camp_fire_operations.id AND child.status='PREPARED')")){s.setString(1,fireOperation);s.executeUpdate();}return null;});
    }
    private void refreshLocks()throws SQLException,IOException {
        var positions=new HashSet<ConsumptionContainers.Position>();
        for(var meal:storage.query(CampLonghouseStore::pending))for(var image:ConsumptionContainers.decode(meal.containers()))positions.add(image.position());
        locked.clear();locked.addAll(positions);
    }
    private void finish(CampLonghouseStore.Pending meal)throws SQLException,IOException {
        if(storage.query(db->com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.campHeld(db,meal.camp())))return;
        var images=ConsumptionContainers.decode(meal.containers());
        for(var image:images) {
            var p=image.position();var world=Bukkit.getWorld(p.world());
            if(world==null)return;
            world.getChunkAt(p.x()>>4,p.z()>>4).load();
        }
        if(Set.of("GROW","LEVELUP","MAXED","SIFT").contains(meal.result())) {
            for(var world:ConsumptionContainers.apply(images,meal.id(),applied))world.save(true);
            interrupt(meal.result().equals("SIFT")?"sifter-world-saved":"world-saved",meal.camp());
        }
        boolean completed=storage.transaction(db->CampLonghouseStore.complete(db,meal));
        for(var image:images)locked.remove(image.position());
        if(completed && !meal.result().equals("SIFT")) {
            var members=storage.query(db->{var out=new ArrayList<UUID>();try(var s=db.prepareStatement("SELECT uuid FROM camp_members WHERE camp_id=?")){s.setLong(1,meal.camp());try(var r=s.executeQuery()){while(r.next())out.add(UUID.fromString(r.getString(1)));}}return out;});
            String result=switch(meal.result()) {
                case "GROW"->"прогресс вырос";case "LEVELUP"->"уровень повышен";case "MAXED"->"предел уровня";
                case "STARVE"->"не хватает еды, прогресс снижен";case "LEVELDOWN"->"голод, уровень понижен";
                case "STAGNATE"->"нет питания";default->"неизвестный уровень";
            };
            for(var uuid:members){var player=Bukkit.getPlayer(uuid);if(player!=null)player.sendMessage("§6Большой дом: "+result+". Уровень "+meal.level()+", прогресс "+meal.count()+"/"+maximum(meal.level())+". Доход главаря: "+meal.coins()+" монет.");}
        }
    }
    private void interrupt(String phase,long camp) {
        if(camp==Long.getLong("civcraft.test.camp.meal.camp",-1) && phase.equals(System.getProperty("civcraft.test.camp.meal.interruption"))) {
            paused=true;throw new IllegalStateException("Injected camp meal interruption at "+phase);
        }
    }
    private void warn(Exception failure){long now=System.currentTimeMillis();if(now-lastWarning>30000){lastWarning=now;plugin.getLogger().warning("Camp production: "+failure.getMessage());}}
    private record Camp(UUID leader,int level,int count,String world,int x,int y,int z) {}
    private record Due(String id,long camp) {}
    private record Sifter(UUID leader,int level,int count,ConsumptionContainers.Position input,ConsumptionContainers.Position output) {}
    private boolean locked(Block b){return locked.contains(new ConsumptionContainers.Position(b.getWorld().getName(),b.getX(),b.getY(),b.getZ()));}
    private boolean locked(Inventory i){return locked(i.getHolder());}
    private boolean locked(InventoryHolder h){return h instanceof Chest c?locked(c.getBlock()):h instanceof DoubleChest pair && (locked(pair.getLeftSide()) || locked(pair.getRightSide()));}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void open(InventoryOpenEvent e){if(locked(e.getInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void click(InventoryClickEvent e){if(locked(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drag(InventoryDragEvent e){if(locked(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void move(InventoryMoveItemEvent e){if(locked(e.getSource()) || locked(e.getDestination()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void burn(BlockBurnEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(EntityExplodeEvent e){e.blockList().removeIf(this::locked);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(BlockExplodeEvent e){e.blockList().removeIf(this::locked);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void extend(BlockPistonExtendEvent e){if(e.getBlocks().stream().anyMatch(b->locked(b) || locked(b.getRelative(e.getDirection()))))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void retract(BlockPistonRetractEvent e){if(e.getBlocks().stream().anyMatch(b->locked(b) || locked(b.getRelative(e.getDirection()))))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void place(BlockPlaceEvent e){if(locked(e.getBlock()) || Arrays.stream(BlockFace.values()).filter(f->f.getModY()==0 && Math.abs(f.getModX())+Math.abs(f.getModZ())==1).anyMatch(f->locked(e.getBlock().getRelative(f))))e.setCancelled(true);}
}
