package com.avrgaming.civcraft.modern.town;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.culture.BiomePotential;
import com.avrgaming.civcraft.modern.town.ConsumptionContainers.*;
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
import org.bukkit.inventory.meta.Damageable;

/** Original resource producers with a shared durable physical-container barrier. */
public final class ProductionService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final FisheryLoot loot;
    private final TrommelLoot trommel;
    private final BiomePotential biomes;
    private final Set<Position> locked=new HashSet<>();
    private final NamespacedKey applied;
    private final long fisheryPeriod,trommelPeriod;
    private boolean ready,paused;
    private long lastWarning;
    private record Farm(long id,long town,String world,int x,int y,int z,String type) {}
    private record Input(int pool,Position position) {}
    public ProductionService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException,IOException {
        this.plugin=plugin;this.storage=storage;applied=new NamespacedKey(plugin,"last-resource-production");
        fisheryPeriod=Long.getLong("civcraft.test.fishery.periodMillis",20000L);if(fisheryPeriod<1000 || fisheryPeriod>20000)throw new IllegalArgumentException("Invalid fishery period");
        trommelPeriod=Long.getLong("civcraft.test.trommel.periodMillis",1000L);if(trommelPeriod<1000 || trommelPeriod>60000)throw new IllegalArgumentException("Invalid trommel period");
        loot=new FisheryLoot(new File(plugin.getDataFolder(),"civcraft/data/structures.yml"));
        trommel=new TrommelLoot(new File(plugin.getDataFolder(),"civcraft/data/structures.yml"));
        for(String item:trommel.items())plugin.nativeItems().require(item);
        biomes=new BiomePotential(YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/culture.yml")).getMapList("culture_biomes"));
        // Fail at startup if a migrated native fish definition is missing, before any rod is spent.
        for(int tier=0;tier<=4;tier++)for(int group=0;group<=3;group++)for(int roll=0;roll<100;roll++)for(int junk:List.of(0,31,51,71,91)) {
            String item=FisheryLoot.item(tier,group,roll,junk);if(item.startsWith("mat_"))plugin.nativeItems().require(item);
        }
        storage.transaction(db->{ProductionStore.install(db);return null;});
        for(var operation:storage.query(ProductionStore::pending))lock(operation);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    public void requireUnlocked(long build)throws SQLException {storage.query(db->{ProductionStore.requireBuildUnlocked(db,build);return null;});}
    public List<String> poolLines(long town,int slot)throws SQLException {
        if(slot<0 || slot>3)throw new IllegalArgumentException("Неизвестный бассейн.");
        return slot<storage.query(db->ProductionStore.fisheryLevel(db,town))?List.of("Бассейн "+(slot+1),"Работает","Удочки в сундук","Цикл: 20 сек."):
            List.of("Бассейн "+(slot+1),"Не работает","Нужно улучшение","Рыбная ферма "+(slot+1));
    }
    public Optional<String> description(long town,String type)throws SQLException {
        if(type.equals("ti_fish_hatchery"))return Optional.of("Рыбная ферма: уровень "+storage.query(db->ProductionStore.fisheryLevel(db,town))+". Один цикл за 20 секунд; удочки в бассейнах, улов в общем сундуке.");
        if(type.equals("s_trommel"))return Optional.of("Дробилка: уровень "+storage.query(db->ProductionStore.trommelLevel(db,town))+". Один блок за секунду; входной и выходной сундуки.");
        return Optional.empty();
    }
    public void tick() {
        if(paused)return;
        try {
            for(var operation:storage.query(ProductionStore::pending))finish(operation);
            if(!ready){storage.transaction(db->{ProductionStore.start(db,"ti_fish_hatchery",fisheryPeriod,System.currentTimeMillis()/fisheryPeriod);ProductionStore.start(db,"s_trommel",trommelPeriod,System.currentTimeMillis()/trommelPeriod);return null;});ready=true;}
            var farms=storage.query(db->{var result=new ArrayList<Farm>();
                try(var s=db.createStatement();var r=s.executeQuery("SELECT id,town_id,world,x,y,z,structure_id FROM structure_builds WHERE structure_id IN ('ti_fish_hatchery','s_trommel') AND status='COMPLETE' AND town_id IS NOT NULL ORDER BY id")){while(r.next())result.add(new Farm(r.getLong(1),r.getLong(2),r.getString(3),r.getInt(4),r.getInt(5),r.getInt(6),r.getString(7)));}return result;
            });
            for(var farm:farms)try{long period=farm.type().equals("s_trommel")?trommelPeriod:fisheryPeriod;produce(farm,System.currentTimeMillis()/period,period);}catch(SQLException|IOException|RuntimeException failure){warn("Producer "+farm.id()+" failed",failure);if(paused)return;}
        }catch(SQLException|IOException|RuntimeException failure){warn("Resource production recovery failed",failure);}
    }
    private void warn(String message,Exception failure){if(System.currentTimeMillis()-lastWarning>30000){lastWarning=System.currentTimeMillis();plugin.getLogger().severe(message+": "+failure.getMessage());}}
    private void produce(Farm farm,long tick,long period)throws SQLException,IOException {
        var before=storage.transaction(db->ProductionStore.initialize(db,farm.id(),period,tick));if(before.tick()>=tick)return;
        if(farm.type().equals("s_trommel")){crush(farm,before,tick);return;}
        var inputs=new ArrayList<Input>();var destinations=new ArrayList<Position>();
        storage.query(db->{try(var s=db.prepareStatement("SELECT world,x,y,z,chest_id FROM structure_chests WHERE build_id=? ORDER BY chest_id,x,y,z")) {
            s.setLong(1,farm.id());try(var r=s.executeQuery()){while(r.next()) {
                var position=new Position(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4));int group=r.getInt(5);
                if(group>=0 && group<4)inputs.add(new Input(group,position));else if(group==4)destinations.add(position);
            }}
        }return null;});
        if(inputs.size()!=4 || inputs.stream().map(Input::pool).distinct().count()!=4 || destinations.size()!=2)return;
        var all=new LinkedHashSet<Position>();inputs.forEach(i->all.add(i.position()));all.addAll(destinations);
        if(all.size()!=6)throw new IllegalStateException("Fishery chest groups overlap");
        if(all.stream().anyMatch(locked::contains))return;
        for(var p:all){var world=Bukkit.getWorld(p.world());if(world==null || !world.isChunkLoaded(p.x()>>4,p.z()>>4))return;}
        var sources=ConsumptionContainers.capture(inputs.stream().map(Input::position).toList());
        var target=ConsumptionContainers.capture(destinations);
        var health=storage.query(db->com.avrgaming.civcraft.modern.war.BuildingDamageStore.health(db,farm.id()));
        boolean active=health!=null && health.hitpoints()>0;
        boolean space=target.stream().anyMatch(i->Arrays.stream(i.contents()).anyMatch(s->s==null || s.getType().isAir()));
        int level=storage.query(db->ProductionStore.fisheryLevel(db,farm.town()));
        String biome=biomes.legacy(Bukkit.getWorld(farm.world()).getBiome(farm.x(),farm.y(),farm.z()).getKey().getKey());
        var contents=sources.stream().map(Image::contents).toList();int produced=0;
        long until=active && space?Math.min(tick,before.tick()+64):tick;
        if(active && space)for(long cycle=before.tick();cycle<until;cycle++)for(int pool=0;pool<inputs.size();pool++) {
            if(inputs.get(pool).pool()>=level)continue;
            var inventory=contents.get(pool);
            for(int slot=0;slot<inventory.length;slot++) {
                var rod=inventory[slot];if(rod==null || rod.getType()!=Material.FISHING_ROD)continue;
                var meta=(Damageable)rod.getItemMeta();int damage=FisheryLoot.damageAfterUse(meta.getDamage());
                if(damage<0)inventory[slot]=null;else{meta.setDamage(damage);rod.setItemMeta(meta);}
                String item=loot.roll(level,biome,java.util.concurrent.ThreadLocalRandom.current());
                target=ConsumptionContainers.addBestEffort(target,item.startsWith("mat_")?plugin.nativeItems().create(item,1):new ItemStack(Material.valueOf(item)));
                produced++;break;
            }
        }
        // Empty or inactive farms do not accumulate an unbounded backlog of idle minutes.
        if(produced==0)until=tick;
        var after=new ArrayList<Image>();for(int i=0;i<sources.size();i++)after.add(new Image(sources.get(i).position(),contents.get(i)));after.addAll(target);
        byte[] payload=ConsumptionContainers.encode(produced==0?List.of():after);int count=produced;long next=until;
        var pending=storage.transaction(db->ProductionStore.prepare(db,UUID.randomUUID().toString(),before,next,count,payload));
        lock(pending);if(produced>0)interrupt("prepared",farm.id());finish(pending);
    }
    private void crush(Farm farm,ProductionStore.Clock before,long tick)throws SQLException,IOException {
        var sources=new ArrayList<Position>();var destinations=new ArrayList<Position>();
        storage.query(db->{try(var s=db.prepareStatement("SELECT world,x,y,z,chest_id FROM structure_chests WHERE build_id=? ORDER BY chest_id,x,y,z")) {
            s.setLong(1,farm.id());try(var r=s.executeQuery()){while(r.next()){var p=new Position(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4));if(r.getInt(5)==1)sources.add(p);else if(r.getInt(5)==2)destinations.add(p);}}
        }return null;});
        if(sources.size()!=2 || destinations.size()!=2)return;
        var all=new LinkedHashSet<>(sources);all.addAll(destinations);if(all.size()!=4)throw new IllegalStateException("Trommel chest groups overlap");
        if(all.stream().anyMatch(locked::contains))return;
        for(var p:all){var w=Bukkit.getWorld(p.world());if(w==null || !w.isChunkLoaded(p.x()>>4,p.z()>>4))return;}
        var input=ConsumptionContainers.capture(sources);var output=ConsumptionContainers.capture(destinations);var contents=input.stream().map(Image::contents).toList();
        var health=storage.query(db->com.avrgaming.civcraft.modern.war.BuildingDamageStore.health(db,farm.id()));
        boolean active=health!=null && health.hitpoints()>0;
        boolean space=output.stream().anyMatch(i->Arrays.stream(i.contents()).anyMatch(s->s==null || s.getType().isAir()));
        int level=storage.query(db->ProductionStore.trommelLevel(db,farm.town()));double extraction=storage.townGoodieEffects(farm.town()).value("buff_extraction");
        int produced=0;long until=active && space?Math.min(tick,before.tick()+64):tick;
        if(active && space)for(long cycle=before.tick();cycle<until;cycle++) {
            boolean consumed=false;
            for(var inventory:contents){for(int slot=0;slot<inventory.length;slot++) {
                var stack=inventory[slot];if(stack==null || !TrommelLoot.accepts(stack.getType().name(),level))continue;
                String result=trommel.roll(stack.getType().name(),extraction,java.util.concurrent.ThreadLocalRandom.current());
                if(stack.getAmount()==1)inventory[slot]=null;else stack.setAmount(stack.getAmount()-1);
                output=ConsumptionContainers.addBestEffort(output,result.startsWith("mat_")?plugin.nativeItems().create(result,1):new ItemStack(Material.valueOf(result)));
                produced++;consumed=true;break;
            }if(consumed)break;}
            if(!consumed)break;
        }
        if(produced==0)until=tick;
        var after=new ArrayList<Image>();for(int i=0;i<input.size();i++)after.add(new Image(input.get(i).position(),contents.get(i)));after.addAll(output);
        byte[] payload=ConsumptionContainers.encode(produced==0?List.of():after);int count=produced;long next=until;
        var pending=storage.transaction(db->ProductionStore.prepare(db,UUID.randomUUID().toString(),before,next,count,payload));lock(pending);if(produced>0)interrupt("prepared",farm.id());finish(pending);
    }
    private void lock(ProductionStore.Pending operation)throws IOException{for(var image:ConsumptionContainers.decode(operation.containers()))locked.add(image.position());}
    private void finish(ProductionStore.Pending operation)throws SQLException,IOException {
        var images=ConsumptionContainers.decode(operation.containers());lock(operation);
        if(operation.produced()>0){for(var world:ConsumptionContainers.apply(images,operation.id(),applied))world.save(true);interrupt("world-saved",operation.before().build());}
        storage.transaction(db->ProductionStore.complete(db,operation.id()));for(var image:images)locked.remove(image.position());
    }
    private void interrupt(String phase,long build){if((build==Long.getLong("civcraft.test.production.build",-1) && phase.equals(System.getProperty("civcraft.test.production.interruption"))) || (build==Long.getLong("civcraft.test.fishery.build",-1) && phase.equals(System.getProperty("civcraft.test.fishery.interruption")))){paused=true;throw new IllegalStateException("Injected production interruption at "+phase);}}
    private boolean locked(Block b){return locked.contains(new Position(b.getWorld().getName(),b.getX(),b.getY(),b.getZ()));}
    private boolean locked(Inventory inventory){return locked(inventory.getHolder());}
    private boolean locked(InventoryHolder holder){if(holder instanceof Chest c)return locked(c.getBlock());if(holder instanceof DoubleChest c)return locked(c.getLeftSide()) || locked(c.getRightSide());return false;}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void open(InventoryOpenEvent e){if(locked(e.getInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void click(InventoryClickEvent e){if(locked(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drag(InventoryDragEvent e){if(locked(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void move(InventoryMoveItemEvent e){if(locked(e.getSource()) || locked(e.getDestination()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void burn(BlockBurnEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(EntityExplodeEvent e){e.blockList().removeIf(this::locked);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(BlockExplodeEvent e){e.blockList().removeIf(this::locked);}
}
