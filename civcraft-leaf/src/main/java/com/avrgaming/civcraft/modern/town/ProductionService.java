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
    private final QuarryLoot quarry;
    private final MobGrinderLoot grinder;
    private final long grinderPeriod=20000L;
    private final WindmillPlan windmill;
    private final FarmGrowthPlan farmGrowth;
    private final Map<String,Double> farmBiomeRates=new LinkedHashMap<>();
    private final long farmGrowthPeriod;
    private static final Map<Material,FarmGrowthPlan.Crop> FARM_CROPS=Arrays.stream(FarmGrowthPlan.Crop.values()).collect(java.util.stream.Collectors.toUnmodifiableMap(c->Material.valueOf(c.name()),c->c));
    private static final Map<Integer,Material> SEEDS=Map.of(295,Material.WHEAT_SEEDS,391,Material.CARROT,392,Material.POTATO);
    private final BiomePotential biomes;
    private final Set<Position> locked=new HashSet<>();
    private final NamespacedKey applied;
    private final long fisheryPeriod,trommelPeriod,quarryPeriod,windmillPeriod;
    private boolean ready,paused;
    private long lastWarning;
    private record Farm(long id,long town,String world,int x,int y,int z,String type) {}
    private record Input(int pool,Position position) {}
    public ProductionService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException,IOException {
        this.plugin=plugin;this.storage=storage;applied=new NamespacedKey(plugin,"last-resource-production");
        fisheryPeriod=Long.getLong("civcraft.test.fishery.periodMillis",20000L);if(fisheryPeriod<1000 || fisheryPeriod>20000)throw new IllegalArgumentException("Invalid fishery period");
        trommelPeriod=Long.getLong("civcraft.test.trommel.periodMillis",1000L);if(trommelPeriod<1000 || trommelPeriod>60000)throw new IllegalArgumentException("Invalid trommel period");
        quarryPeriod=Long.getLong("civcraft.test.quarry.periodMillis",1000L);if(quarryPeriod<1000 || quarryPeriod>60000)throw new IllegalArgumentException("Invalid quarry period");
        windmillPeriod=Long.getLong("civcraft.test.windmill.periodMillis",60000L);if(windmillPeriod<1000 || windmillPeriod>60000)throw new IllegalArgumentException("Invalid windmill period");
        loot=new FisheryLoot(new File(plugin.getDataFolder(),"civcraft/data/structures.yml"));
        trommel=new TrommelLoot(new File(plugin.getDataFolder(),"civcraft/data/structures.yml"));
        quarry=new QuarryLoot(new File(plugin.getDataFolder(),"civcraft/data/structures.yml"));plugin.nativeItems().require("mat_tungsten_ore");
        grinder=new MobGrinderLoot(new File(plugin.getDataFolder(),"civcraft/data/structures.yml"));for(String item:grinder.items())plugin.nativeItems().require(item);
        windmill=new WindmillPlan(new File(plugin.getDataFolder(),"civcraft/data/structures.yml"));
        var farmConfig=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/structures.yml"));
        farmGrowth=new FarmGrowthPlan(farmConfig.getInt("farm.grows_per_tick"));
        long normalFarmPeriod=Math.multiplyExact(farmConfig.getLong("farm.grow_tick_rate"),1000);
        farmGrowthPeriod=Long.getLong("civcraft.test.farm.periodMillis",normalFarmPeriod);if(farmGrowthPeriod<1000 || farmGrowthPeriod>normalFarmPeriod)throw new IllegalArgumentException("Invalid farm period");
        for(var entry:farmConfig.getMapList("structures"))if("ti_farm".equals(entry.get("id")) && entry.get("components") instanceof List<?> components)for(Object value:components)if(value instanceof Map<?,?> component && "ActivateOnBiome".equals(component.get("name")) && "GROWTH".equals(component.get("attribute"))){double multiplier=Double.parseDouble(String.valueOf(component.get("value")));if(!Double.isFinite(multiplier) || multiplier<0)throw new IllegalArgumentException("Invalid farm biome multiplier");for(String biome:String.valueOf(component.get("biomes")).split(","))farmBiomeRates.putIfAbsent(biome.trim().toUpperCase(Locale.ROOT),multiplier);}
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
        if(type.equals("ti_quarry"))return Optional.of("Карьер: уровень "+storage.query(db->ProductionStore.quarryLevel(db,town))+". Цикл: 1 секунда. Кирки во входной сундук; Эффективность увеличивает выход и износ.");
        if(type.equals("ti_windmill"))return Optional.of("Мельница засевает до "+windmill.capacity(machinery(town))+" грядок в минуту на восьми соседних участках ферм. Семена, морковь и картофель положите в сундук мельницы. Во время войны посев приостановлен.");
        if(type.equals("ti_farm"))return Optional.of("Ферма выращивает культуры в своём чанке. Рост зависит от показателя города; на равнине действует множитель "+farmBiomeRates.getOrDefault("PLAINS",1.0)+". Цикл: "+farmGrowthPeriod/1000+" сек. Пропущенные при выгрузке чанка циклы сохраняются.");
        if(type.equals("ti_pasture"))return Optional.of("Пастбище позволяет разводить животных. Лимит: "+plugin.pastures().maximum()+" рождённых животных на пастбище, общий для всех видов. Смерть потомка освобождает место. Родителей нужно привести самостоятельно.");
        if(type.equals("s_mob_grinder"))return Optional.of("Мобоферма перерабатывает яйца CivCraft раз в 20 секунд. Кладите во входной сундук только яйца; награды забирайте из выходного. Племенной строй повышает добычу.");
        return Optional.empty();
    }
    public void tick() {
        if(paused)return;
        try {
            for(var operation:storage.query(ProductionStore::pending))finish(operation);
            if(!ready){storage.transaction(db->{ProductionStore.start(db,"ti_fish_hatchery",fisheryPeriod,System.currentTimeMillis()/fisheryPeriod);ProductionStore.start(db,"s_trommel",trommelPeriod,System.currentTimeMillis()/trommelPeriod);ProductionStore.start(db,"ti_quarry",quarryPeriod,System.currentTimeMillis()/quarryPeriod);ProductionStore.start(db,"ti_windmill",windmillPeriod,System.currentTimeMillis()/windmillPeriod);ProductionStore.start(db,"s_mob_grinder",grinderPeriod,System.currentTimeMillis()/grinderPeriod);FarmGrowthStore.start(db,farmGrowthPeriod,System.currentTimeMillis()/farmGrowthPeriod);return null;});ready=true;}
            var farms=storage.query(db->{var result=new ArrayList<Farm>();
                try(var s=db.createStatement();var r=s.executeQuery("SELECT id,town_id,world,x,y,z,structure_id FROM structure_builds WHERE structure_id IN ('ti_fish_hatchery','s_trommel','ti_quarry','ti_windmill','ti_farm','s_mob_grinder') AND status='COMPLETE' AND town_id IS NOT NULL ORDER BY id")){while(r.next())result.add(new Farm(r.getLong(1),r.getLong(2),r.getString(3),r.getInt(4),r.getInt(5),r.getInt(6),r.getString(7)));}return result;
            });
            for(var farm:farms)try{long period=switch(farm.type()){case "s_trommel"->trommelPeriod;case "ti_quarry"->quarryPeriod;case "ti_windmill"->windmillPeriod;case "s_mob_grinder"->grinderPeriod;default->fisheryPeriod;};produce(farm,System.currentTimeMillis()/period,period);}catch(SQLException|IOException|RuntimeException failure){warn("Producer "+farm.id()+" failed",failure);if(paused)return;}
        }catch(SQLException|IOException|RuntimeException failure){warn("Resource production recovery failed",failure);}
    }
    private void warn(String message,Exception failure){if(System.currentTimeMillis()-lastWarning>30000){lastWarning=System.currentTimeMillis();plugin.getLogger().severe(message+": "+failure.getMessage());}}
    private void produce(Farm farm,long tick,long period)throws SQLException,IOException {
        if(farm.type().equals("ti_farm")){growFarm(farm);return;}
        var before=storage.transaction(db->ProductionStore.initialize(db,farm.id(),period,tick));if(before.tick()>=tick)return;
        if(farm.type().equals("s_mob_grinder")){grind(farm,before,tick);return;}
        if(farm.type().equals("ti_windmill")){plant(farm,before,tick);return;}
        if(farm.type().equals("s_trommel") || farm.type().equals("ti_quarry")){extract(farm,before,tick);return;}
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
    private void extract(Farm farm,ProductionStore.Clock before,long tick)throws SQLException,IOException {
        boolean isQuarry=farm.type().equals("ti_quarry");int sourceGroup=isQuarry?0:1;
        var sources=new ArrayList<Position>();var destinations=new ArrayList<Position>();
        storage.query(db->{try(var s=db.prepareStatement("SELECT world,x,y,z,chest_id FROM structure_chests WHERE build_id=? ORDER BY chest_id,x,y,z")) {
            s.setLong(1,farm.id());try(var r=s.executeQuery()){while(r.next()){var p=new Position(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4));if(r.getInt(5)==sourceGroup)sources.add(p);else if(r.getInt(5)==sourceGroup+1)destinations.add(p);}}
        }return null;});
        if(sources.size()!=2 || destinations.size()!=2)return;
        var all=new LinkedHashSet<>(sources);all.addAll(destinations);if(all.size()!=4)throw new IllegalStateException("Trommel chest groups overlap");
        if(all.stream().anyMatch(locked::contains))return;
        for(var p:all){var w=Bukkit.getWorld(p.world());if(w==null || !w.isChunkLoaded(p.x()>>4,p.z()>>4))return;}
        var input=ConsumptionContainers.capture(sources);var output=ConsumptionContainers.capture(destinations);var contents=input.stream().map(Image::contents).toList();
        var health=storage.query(db->com.avrgaming.civcraft.modern.war.BuildingDamageStore.health(db,farm.id()));
        boolean active=health!=null && health.hitpoints()>0;
        boolean space=output.stream().anyMatch(i->Arrays.stream(i.contents()).anyMatch(s->s==null || s.getType().isAir()));
        int level=storage.query(db->isQuarry?ProductionStore.quarryLevel(db,farm.town()):ProductionStore.trommelLevel(db,farm.town()));double extraction=storage.townGoodieEffects(farm.town()).value("buff_extraction");
        String government=isQuarry?plugin.governments().townGovernment(farm.town()):"";
        int produced=0;long until=active && space?Math.min(tick,before.tick()+64):tick;
        if(active && space)for(long cycle=before.tick();cycle<until;cycle++) {
            boolean consumed=false;
            for(var inventory:contents){for(int slot=0;slot<inventory.length;slot++) {
                var stack=inventory[slot];if(stack==null)continue;String material=stack.getType().name();
                if(!(isQuarry?QuarryLoot.accepts(material,level):TrommelLoot.accepts(material,level)))continue;
                String result;int quantity=1;
                if(isQuarry) {
                    int efficiency=stack.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.EFFICIENCY);
                    var reward=quarry.roll(material,efficiency,extraction,government,java.util.concurrent.ThreadLocalRandom.current());result=reward.item();quantity=reward.amount();
                    var meta=(Damageable)stack.getItemMeta();int damage=QuarryLoot.damageAfterUse(material,meta.getDamage(),QuarryLoot.modifier(efficiency),stack.getAmount());inventory[slot]=null;
                    if(damage>=0){meta.setDamage(damage);stack.setItemMeta(meta);boolean returned=false;for(var target:contents){for(int free=0;free<target.length;free++)if(target[free]==null || target[free].getType().isAir()){target[free]=stack;returned=true;break;}if(returned)break;}}
                } else {
                    result=trommel.roll(material,extraction,java.util.concurrent.ThreadLocalRandom.current());
                    if(stack.getAmount()==1)inventory[slot]=null;else stack.setAmount(stack.getAmount()-1);
                }
                for(int remaining=quantity;remaining>0;remaining-=64){int amount=Math.min(64,remaining);output=ConsumptionContainers.addBestEffort(output,result.startsWith("mat_")?plugin.nativeItems().create(result,amount):new ItemStack(Material.valueOf(result),amount));}
                produced=Math.addExact(produced,quantity);consumed=true;break;
            }if(consumed)break;}
            if(!consumed)break;
        }
        if(produced==0)until=tick;
        var after=new ArrayList<Image>();for(int i=0;i<input.size();i++)after.add(new Image(input.get(i).position(),contents.get(i)));after.addAll(output);
        byte[] payload=ConsumptionContainers.encode(produced==0?List.of():after);int count=produced;long next=until;
        var pending=storage.transaction(db->ProductionStore.prepare(db,UUID.randomUUID().toString(),before,next,count,payload));lock(pending);if(produced>0)interrupt("prepared",farm.id());finish(pending);
    }
    private void grind(Farm farm,ProductionStore.Clock before,long tick)throws SQLException,IOException {
        var sources=new ArrayList<Position>();var destinations=new ArrayList<Position>();
        storage.query(db->{try(var s=db.prepareStatement("SELECT world,x,y,z,chest_id FROM structure_chests WHERE build_id=? ORDER BY chest_id,x,y,z")){
            s.setLong(1,farm.id());try(var r=s.executeQuery()){while(r.next()){var p=new Position(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4));if(r.getInt(5)==1)sources.add(p);else if(r.getInt(5)==2)destinations.add(p);}}
        }return null;});
        if(sources.size()!=2 || destinations.size()!=2)return;
        var positions=new LinkedHashSet<>(sources);positions.addAll(destinations);if(positions.size()!=4)throw new IllegalStateException("Mob grinder chest groups overlap");
        if(positions.stream().anyMatch(locked::contains))return;
        for(var p:positions){var world=Bukkit.getWorld(p.world());if(world==null || !world.isChunkLoaded(p.x()>>4,p.z()>>4))return;}
        var input=ConsumptionContainers.capture(sources);var output=ConsumptionContainers.capture(destinations);var contents=input.stream().map(Image::contents).toList();
        var health=storage.query(db->com.avrgaming.civcraft.modern.war.BuildingDamageStore.health(db,farm.id()));
        boolean active=health!=null && health.hitpoints()>0;
        boolean space=output.stream().anyMatch(i->Arrays.stream(i.contents()).anyMatch(s->s==null || s.getType().isAir()));
        double extraction=storage.townGoodieEffects(farm.town()).value("buff_extraction");String government=plugin.governments().townGovernment(farm.town());
        int consumed=0;long until=active && space?Math.min(tick,before.tick()+64):tick;
        if(active && space)for(long cycle=before.tick();cycle<until;cycle++){
            boolean processed=false;int beforeConsumed=consumed;
            for(var inventory:contents){for(int slot=0;slot<inventory.length;slot++){
                var stack=inventory[slot];String id=plugin.nativeItems().id(stack);if(id==null)continue;
                // The original removes each custom input it visits, including non-egg items before an egg.
                if(stack.getAmount()==1)inventory[slot]=null;else stack.setAmount(stack.getAmount()-1);consumed++;
                var rewards=grinder.roll(id,extraction,government,java.util.concurrent.ThreadLocalRandom.current());
                for(var reward:rewards)output=ConsumptionContainers.addBestEffort(output,plugin.nativeItems().create(reward.item(),reward.amount()));
                if(MobGrinderLoot.egg(id)){processed=true;break;}
            }if(processed)break;}
            if(consumed==beforeConsumed)break;
        }
        if(consumed==0)until=tick;
        var after=new ArrayList<Image>();for(int i=0;i<input.size();i++)after.add(new Image(input.get(i).position(),contents.get(i)));after.addAll(output);
        byte[] payload=ConsumptionContainers.encode(consumed==0?List.of():after);int count=consumed;long next=until;
        var pending=storage.transaction(db->ProductionStore.prepare(db,UUID.randomUUID().toString(),before,next,count,payload));lock(pending);if(consumed>0)interrupt("prepared",farm.id());finish(pending);
    }
    private boolean machinery(long town)throws SQLException {
        return storage.query(db->{try(var s=db.prepareStatement("SELECT 1 FROM towns t JOIN civ_technologies c ON c.civ_id=t.civ_id WHERE t.id=? AND c.tech_id='tech_machinery'")){s.setLong(1,town);try(var r=s.executeQuery()){return r.next();}}});
    }
    private void plant(Farm farm,ProductionStore.Clock before,long tick)throws SQLException,IOException {
        long now=System.currentTimeMillis();boolean war=storage.query(db->{try(var s=db.prepareStatement("SELECT 1 FROM war_sessions WHERE state='ACTIVE' AND start_at<=? AND end_at>?")){s.setLong(1,now);s.setLong(2,now);try(var r=s.executeQuery()){return r.next();}}});
        var health=storage.query(db->com.avrgaming.civcraft.modern.war.BuildingDamageStore.health(db,farm.id()));
        var beds=new ArrayList<WindmillStore.Bed>();List<Image> after=List.of();
        if(!war && health!=null && health.hitpoints()>0) {
            var positions=storage.query(db->{var result=new ArrayList<Position>();try(var s=db.prepareStatement("SELECT world,x,y,z FROM structure_chests WHERE build_id=? AND chest_id=0 ORDER BY x,y,z")){s.setLong(1,farm.id());try(var r=s.executeQuery()){while(r.next())result.add(new Position(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4)));}}return result;});
            if(positions.size()!=2 || positions.stream().anyMatch(locked::contains))return;
            var source=ConsumptionContainers.capture(positions);var counts=ConsumptionContainers.counts(source,SEEDS);
            var stock=Map.of(WindmillPlan.Crop.WHEAT,counts.getOrDefault(295,0L),WindmillPlan.Crop.CARROTS,counts.getOrDefault(391,0L),WindmillPlan.Crop.POTATOES,counts.getOrDefault(392,0L));
            var candidates=new ArrayList<WindmillPlan.Spot>();
            if(stock.values().stream().anyMatch(v->v>0)) {
                var world=Bukkit.getWorld(farm.world());if(world==null)throw new IllegalStateException("Windmill world unavailable");
                for(var plot:storage.query(db->FarmPlots.adjacent(db,farm.world(),farm.x(),farm.z()))) {
                    var snapshot=world.getChunkAt(plot.x(),plot.z()).getChunkSnapshot();
                    for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int y=world.getMinHeight();y<world.getMaxHeight()-1;y++) {
                        int bx=plot.x()*16+x,bz=plot.z()*16+z;
                        if(snapshot.getBlockType(x,y,z)==Material.FARMLAND && snapshot.getBlockType(x,y+1,z).isAir() && !locked.contains(new Position(farm.world(),bx,y+1,bz)) && !locked.contains(new Position(farm.world(),bx,y,bz)))candidates.add(new WindmillPlan.Spot(plot.farm(),farm.world(),bx,y+1,bz));
                    }
                }
            }
            var plan=windmill.plan(candidates,stock,machinery(farm.town()),false,java.util.concurrent.ThreadLocalRandom.current());
            for(var seed:plan.plants()){var p=seed.spot();var world=Bukkit.getWorld(p.world());beds.add(new WindmillStore.Bed(p,seed.crop(),world.getBlockAt(p.x(),p.y(),p.z()).getBlockData().getAsString()));}
            if(!beds.isEmpty())after=ConsumptionContainers.after(source,Map.of(295,plan.remaining().get(WindmillPlan.Crop.WHEAT),391,plan.remaining().get(WindmillPlan.Crop.CARROTS),392,plan.remaining().get(WindmillPlan.Crop.POTATOES)),SEEDS);
        }
        byte[] payload=ConsumptionContainers.encode(after);
        var pending=storage.transaction(db->WindmillStore.prepare(db,UUID.randomUUID().toString(),before,tick,beds,payload,System.currentTimeMillis()));lock(pending);if(!beds.isEmpty())interrupt("prepared",farm.id());finish(pending);
    }
    private void growFarm(Farm farm)throws SQLException,IOException {
        var registered=storage.query(db->FarmPlots.registered(db,farm.world(),farm.x(),farm.z()));if(registered.isEmpty() || registered.get().farm()!=farm.id())return;
        var state=storage.transaction(db->FarmGrowthStore.observe(db,farm.id(),farmGrowthPeriod,System.currentTimeMillis()/farmGrowthPeriod));
        if(state.missed()==0)return;
        var world=Bukkit.getWorld(farm.world());int cx=Math.floorDiv(farm.x(),16),cz=Math.floorDiv(farm.z(),16);
        if(world==null || !world.isChunkLoaded(cx,cz))return;
        var health=storage.query(db->com.avrgaming.civcraft.modern.war.BuildingDamageStore.health(db,farm.id()));
        var cells=new ArrayList<FarmGrowthStore.Cell>();
        if(health!=null && health.hitpoints()>0){
            var snapshot=world.getChunkAt(cx,cz).getChunkSnapshot();var plants=new ArrayList<FarmGrowthPlan.Plant>();
            int[][] offsets={{-1,0},{1,0},{0,-1},{0,1}};
            for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int y=world.getMinHeight();y<world.getMaxHeight();y++){
                var crop=FARM_CROPS.get(snapshot.getBlockType(x,y,z));if(crop==null)continue;
                if(!(snapshot.getBlockData(x,y,z) instanceof org.bukkit.block.data.Ageable age))continue;
                int bx=cx*16+x,bz=cz*16+z;if(locked.contains(new Position(farm.world(),bx,y,bz)))continue;
                var neighbors=new ArrayList<String>();if(crop.stem())for(var offset:offsets){int nx=x+offset[0],nz=z+offset[1];neighbors.add(nx<0 || nx>=16 || nz<0 || nz>=16?null:snapshot.getBlockType(nx,y,nz).name());}
                plants.add(new FarmGrowthPlan.Plant(new FarmGrowthPlan.Position(farm.world(),bx,y,bz),crop,age.getAge(),neighbors));
            }
            double growth=storage.findTown(farm.town()).orElseThrow().growth();
            double multiplier=farmBiomeRates.getOrDefault(biomes.legacy(world.getBiome(cx*16+8,64,cz*16+8).getKey().getKey()),1.0);
            for(var change:farmGrowth.plan(plants,growth,multiplier,true,java.util.concurrent.ThreadLocalRandom.current())){
                var p=change.position();var block=world.getBlockAt(p.x(),p.y(),p.z());if(locked(block))continue;
                var after=change.spawn()?Material.valueOf(change.material()).createBlockData():block.getBlockData().clone();
                if(!change.spawn())((org.bukkit.block.data.Ageable)after).setAge(change.age());
                cells.add(new FarmGrowthStore.Cell(p,block.getBlockData().getAsString(),after.getAsString()));
            }
        }
        var payload=ConsumptionContainers.encode(List.of());
        var pending=storage.transaction(db->FarmGrowthStore.prepare(db,UUID.randomUUID().toString(),farm.id(),cells,payload));
        lock(pending);if(!cells.isEmpty())interrupt("prepared",farm.id());finish(pending);
    }
    private void lock(ProductionStore.Pending operation)throws IOException,SQLException {
        for(var image:ConsumptionContainers.decode(operation.containers()))locked.add(image.position());
        for(var bed:storage.query(db->WindmillStore.beds(db,operation.id()))){var p=bed.spot();locked.add(new Position(p.world(),p.x(),p.y(),p.z()));locked.add(new Position(p.world(),p.x(),p.y()-1,p.z()));}
        for(var cell:storage.query(db->FarmGrowthStore.cells(db,operation.id()))){var p=cell.position();locked.add(new Position(p.world(),p.x(),p.y(),p.z()));locked.add(new Position(p.world(),p.x(),p.y()-1,p.z()));}
    }
    private void finish(ProductionStore.Pending operation)throws SQLException,IOException {
        var images=ConsumptionContainers.decode(operation.containers());lock(operation);
        var beds=storage.query(db->WindmillStore.beds(db,operation.id()));var worlds=new LinkedHashSet<World>();
        var growth=storage.query(db->FarmGrowthStore.cells(db,operation.id()));
        for(var cell:growth){var p=cell.position();var world=Bukkit.getWorld(p.world());if(world==null)throw new IllegalStateException("Farm growth world is unavailable");String current=world.getBlockAt(p.x(),p.y(),p.z()).getBlockData().getAsString();if(!current.equals(cell.before()) && !current.equals(cell.after()))throw new IllegalStateException("Prepared farm crop changed: "+p);}
        for(var bed:beds) {
            var p=bed.spot();var world=Bukkit.getWorld(p.world());if(world==null)throw new IllegalStateException("Planting world is unavailable");var block=world.getBlockAt(p.x(),p.y(),p.z());String current=block.getBlockData().getAsString();
            if((!current.equals(bed.before()) && !current.equals(bed.after())) || block.getRelative(0,-1,0).getType()!=Material.FARMLAND)throw new IllegalStateException("Prepared planting bed changed: "+p);
        }
        for(var bed:beds){var p=bed.spot();var world=Bukkit.getWorld(p.world());var block=world.getBlockAt(p.x(),p.y(),p.z());if(!block.getBlockData().getAsString().equals(bed.after()))block.setBlockData(Bukkit.createBlockData(bed.after()),false);worlds.add(world);}
        for(var cell:growth){var p=cell.position();var world=Bukkit.getWorld(p.world());var block=world.getBlockAt(p.x(),p.y(),p.z());if(!block.getBlockData().getAsString().equals(cell.after()))block.setBlockData(Bukkit.createBlockData(cell.after()),false);worlds.add(world);}
        if(operation.produced()>0){worlds.addAll(ConsumptionContainers.apply(images,operation.id(),applied));for(var world:worlds)world.save(true);interrupt("world-saved",operation.before().build());}
        storage.transaction(db->ProductionStore.complete(db,operation.id()));for(var image:images)locked.remove(image.position());
        for(var bed:beds){var p=bed.spot();locked.remove(new Position(p.world(),p.x(),p.y(),p.z()));locked.remove(new Position(p.world(),p.x(),p.y()-1,p.z()));}
        for(var cell:growth){var p=cell.position();locked.remove(new Position(p.world(),p.x(),p.y(),p.z()));locked.remove(new Position(p.world(),p.x(),p.y()-1,p.z()));}
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
    private static boolean controlledCrop(Material type){return switch(type){case WHEAT,CARROTS,POTATOES,NETHER_WART,COCOA,MELON_STEM,PUMPKIN_STEM,ATTACHED_MELON_STEM,ATTACHED_PUMPKIN_STEM,MELON,PUMPKIN->true;default->false;};}
    // CampService advances its own upgraded garden directly; it does not depend on vanilla growth events.
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void grow(BlockGrowEvent e){if(locked(e.getBlock()) || controlledCrop(e.getBlock().getType()) || controlledCrop(e.getNewState().getType()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void fade(BlockFadeEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void physics(BlockPhysicsEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void flow(BlockFromToEvent e){if(locked(e.getToBlock()) || locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void place(BlockPlaceEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interact(org.bukkit.event.player.PlayerInteractEvent e){if(e.getClickedBlock()!=null && locked(e.getClickedBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void entityInteract(org.bukkit.event.entity.EntityInteractEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void entityChange(org.bukkit.event.entity.EntityChangeBlockEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void fertilize(BlockFertilizeEvent e){e.getBlocks().removeIf(b->locked(b.getBlock()));}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void structureGrow(org.bukkit.event.world.StructureGrowEvent e){e.getBlocks().removeIf(b->locked(b.getBlock()));}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void piston(BlockPistonExtendEvent e){if(e.getBlocks().stream().anyMatch(b->locked(b)||locked(b.getRelative(e.getDirection()))))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void piston(BlockPistonRetractEvent e){if(e.getBlocks().stream().anyMatch(b->locked(b)||locked(b.getRelative(e.getDirection()))||locked(b.getRelative(e.getDirection().getOppositeFace()))))e.setCancelled(true);}
}
