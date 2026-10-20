package com.avrgaming.civcraft.modern.town;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.items.LegacyItemMaterials;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.io.*;
import java.sql.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Hourly cottage/temple meals with an inventory barrier and recoverable world intent. */
public final class ConsumptionService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final ConsumptionCatalog catalog;
    private final HappinessRules happiness;
    private final Map<Integer,Material> materials=new HashMap<>();
    private final Set<ConsumptionContainers.Position> locked=new HashSet<>();
    private final NamespacedKey applied;
    private final long period;
    private final double taxationBonus;
    private boolean ready,testingPaused;
    private long lastWarning;
    private record Building(long id,long town,String type) {}

    public ConsumptionService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException,IOException {
        this.plugin=plugin;this.storage=storage;applied=new NamespacedKey(plugin,"last-building-meal");
        period=Long.getLong("civcraft.test.consumption.hourMillis",3600000L);
        if(period<1000 || period>3600000)throw new IllegalArgumentException("Неверный период потребления");
        var folder=new File(plugin.getDataFolder(),"civcraft/data");
        catalog=new ConsumptionCatalog(new File(folder,"structures.yml"));
        happiness=new HappinessRules(new File(folder,"happiness.yml"));
        taxationBonus=YamlConfiguration.loadConfiguration(new File(folder,"techs.yml")).getDouble("taxation_cottage_buff",2);
        if(!Double.isFinite(taxationBonus) || taxationBonus<0)throw new IllegalArgumentException("Неверный налоговый бонус коттеджа");
        var legacy=new LegacyItemMaterials(plugin.getResource("legacy-materials.properties"));
        materials.put(349,legacy.resolve(349,0));
        for(var type:List.of("s_temple","ti_cottage","ti_mine"))for(var level:catalog.levels(type).values())
            for(var id:level.food().keySet())materials.putIfAbsent(id,legacy.resolve(id,0));
        storage.transaction(db->{ConsumptionStore.install(db);return null;});
        storage.setConsumptionCatalog(catalog);
        for(var pending:storage.query(ConsumptionStore::pending))lock(pending);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    private long hour(){return System.currentTimeMillis()/period;}
    public void requireUnlocked(long build)throws SQLException {
        storage.query(db->{com.avrgaming.civcraft.modern.trade.GoodieChestStore.requireStructureUnlocked(db,build,false);return null;});
        for(var pending:storage.query(ConsumptionStore::pending))if(pending.before().build()==build)
            throw new IllegalArgumentException("Питание здания ещё сохраняется. Дождитесь восстановления операции.");
        boolean affected=storage.query(db->{try(var s=db.prepareStatement("SELECT world,x,y,z FROM structure_chests WHERE build_id=?")) {
            s.setLong(1,build);try(var r=s.executeQuery()){while(r.next())if(locked.contains(new ConsumptionContainers.Position(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4))))return true;}
        }return false;});
        if(affected)throw new IllegalArgumentException("Сундуки здания участвуют в незавершённом питании. Дождитесь восстановления операции.");
    }
    public Optional<String> description(long build,String type)throws SQLException {
        if(!List.of("ti_cottage","s_temple","ti_mine").contains(type))return Optional.empty();
        var state=storage.query(db->ConsumptionStore.state(db,build));
        if(state==null)return Optional.empty();
        var level=catalog.levels(type).get(state.level());
        String source=switch(type){case "ti_cottage"->"амбары города";case "ti_mine"->"сундуки шахты (красная пыль)";default->"сундуки храма";};
        return Optional.of("Уровень: "+state.level()+" | Питание: "+state.count()+"/"+(level==null?"?":level.count())+" | Источник еды: "+source+".");
    }
    public void tick() {
        if(testingPaused)return;
        try {
            for(var pending:storage.query(ConsumptionStore::pending))finish(pending);
            if(!ready) {
                storage.transaction(db->{ConsumptionStore.start(db,period,hour());return null;});
                ready=true;
            }
            var buildings=storage.query(db->{
                var result=new ArrayList<Building>();
                try(var s=db.createStatement();var r=s.executeQuery("SELECT id,town_id,structure_id FROM active_structure_builds WHERE status='COMPLETE' AND town_id IS NOT NULL AND structure_id IN ('s_temple','ti_cottage','ti_mine') ORDER BY id")) {
                    while(r.next())result.add(new Building(r.getLong(1),r.getLong(2),r.getString(3)));
                }return result;
            });
            for(var building:buildings) {
                try{consume(building);}
                catch(SQLException|IOException|RuntimeException failure) {
                    warn("Building "+building.id()+" meal failed; pending inventories remain locked",failure);
                    if(testingPaused)return;
                }
            }
        }catch(SQLException|IOException|RuntimeException failure) {
            warn("Building meal recovery failed; pending inventories remain locked",failure);
        }
    }
    private void warn(String context,Exception failure) {
        if(System.currentTimeMillis()-lastWarning>30000) {
            lastWarning=System.currentTimeMillis();plugin.getLogger().severe(context+": "+failure.getMessage());
        }
    }
    private void consume(Building building)throws SQLException,IOException {
        long hour=hour();
        var state=storage.transaction(db->ConsumptionStore.initialize(db,building.id(),period,hour));
        if(state.hour()>=hour)return;
        var positions=storage.query(db->ConsumptionSources.positions(db,building.id(),building.town(),building.type()));
        if(positions.stream().anyMatch(locked::contains))return;
        if(positions.isEmpty() && !building.type().equals("ti_cottage")) {
            boolean any=storage.query(db->{try(var s=db.prepareStatement("SELECT 1 FROM structure_chests WHERE build_id=? LIMIT 1")) {
                s.setLong(1,building.id());try(var r=s.executeQuery()){return r.next();}
            }});
            if(!any)return;
        }
        var before=ConsumptionContainers.capture(positions);
        var wonder=storage.townWonderBonuses(building.town());
        var goodies=storage.townGoodieEffects(building.town());
        boolean cottage=building.type().equals("ti_cottage");
        var cycle=ConsumptionPlan.cycle(catalog.levels(building.type()),state.level(),state.count(),ConsumptionContainers.counts(before,materials),cottage?goodies.cottageFood(wonder.cottageFood()):1,cottage?goodies.cottageExchanges():List.of());
        var after=ConsumptionContainers.after(before,cycle.remaining(),materials);
        boolean poisoned=building.type().equals("ti_cottage") && cycle.state().consumed()
            && storage.query(db->GranaryPoisonStore.remaining(db,building.town()))>0;
        if(poisoned)after=ConsumptionContainers.addBestEffort(after,new org.bukkit.inventory.ItemStack(Material.ROTTEN_FLESH,4));
        var town=storage.findTown(building.town()).orElseThrow();
        var civ=storage.findCiv(town.civId()).orElseThrow();
        // Original Temple.templeCulture also uses cottage rate, including coin happiness.
        double rate=plugin.governments().cottageRate(plugin.governments().townGovernment(town.id()))*happiness.evaluate(town.happiness(),town.unhappiness()).state().coins();
        double bonus=1;
        if(building.type().equals("ti_cottage")) {
            boolean taxation=storage.query(db->{try(var s=db.prepareStatement("SELECT 1 FROM civ_technologies WHERE civ_id=? AND tech_id='tech_taxation'")) {
                s.setLong(1,civ.id());try(var r=s.executeQuery()){return r.next();}
            }});
            if(taxation)bonus=taxationBonus;
        }
        double raw=cycle.rawOutput()*rate;
        if(!Double.isFinite(raw) || raw<0 || raw>Long.MAX_VALUE/2.0)throw new IllegalArgumentException("Неверная выработка постройки");
        double multiplied=Math.round(raw)*bonus;
        if(!Double.isFinite(multiplied) || multiplied>Long.MAX_VALUE/2.0)throw new IllegalArgumentException("Неверный бонус выработки постройки");
        // Original compound integer multiplication applies taxation after rounding.
        long output=poisoned || building.type().equals("ti_mine")?0:building.type().equals("ti_cottage")?com.avrgaming.civcraft.modern.wonder.WonderBonuses.cottageOutput(raw,wonder.cottageIncome(),bonus):(long)multiplied,townCoins=0,civCoins=0,culture=0;
        if(building.type().equals("s_temple"))culture=output;
        else if(building.type().equals("ti_cottage")) {
            double tax=storage.query(db->{try(var s=db.prepareStatement("SELECT income_tax_rate FROM civilizations WHERE id=?")) {
                s.setLong(1,civ.id());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Civilization missing");return r.getDouble(1);}
            }});
            if(!Double.isFinite(tax) || tax<0 || tax>1)throw new IllegalArgumentException("Неверный подоходный налог цивилизации");
            var payout=storage.query(db->com.avrgaming.civcraft.modern.war.OccupationRules.income(output,tax,com.avrgaming.civcraft.modern.war.OccupationRules.captured(db,town.id()),storage.capturedPenalty()));
            civCoins=payout.civilization();townCoins=payout.town();
        }
        long creditTown=townCoins,creditCiv=civCoins,creditCulture=culture;
        var payload=ConsumptionContainers.encode(after);
        var intent=storage.transaction(db->{
            boolean currentPoison=building.type().equals("ti_cottage") && cycle.state().consumed() && GranaryPoisonStore.remaining(db,building.town())>0;
            if(currentPoison!=poisoned)throw new SQLException("Granary poison changed during meal planning");
            return ConsumptionStore.prepare(db,UUID.randomUUID().toString(),state,cycle.state(),hour,creditTown,creditCiv,creditCulture,poisoned,payload);
        });
        lock(intent);if(intent.after().consumed())interrupt("prepared",building.id());finish(intent);
    }
    private void lock(ConsumptionStore.Pending pending)throws IOException {
        for(var image:ConsumptionContainers.decode(pending.containers()))locked.add(image.position());
    }
    private void finish(ConsumptionStore.Pending pending)throws SQLException,IOException {
        var images=ConsumptionContainers.decode(pending.containers());
        for(var image:images)locked.add(image.position());
        // Starvation has no inventory delta: commit its state without a world save.
        if(pending.after().consumed()) {
            var worlds=ConsumptionContainers.apply(images,pending.id(),applied);
            for(var world:worlds)world.save(true);
            interrupt("world-saved",pending.before().build());
        }
        boolean completed=storage.transaction(db->ConsumptionStore.complete(db,pending.id()));
        for(var image:images)locked.remove(image.position());
        if(completed && pending.after().level()!=pending.before().level())plugin.governments().refreshQueuedProduction();
        if(completed) {
            String status=switch(pending.after().result()) {
                case GROW->"прогресс вырос";case LEVELUP->"уровень повышен";case MAXED->"предел уровня";
                case STARVE->"не хватает еды";case LEVELDOWN->"уровень понижен";case STAGNATE->"нет питания";case UNKNOWN->"неизвестный уровень";
            };
            if(pending.poisoned())status="амбар отравлен, дохода нет";
            for(var player:plugin.getServer().getOnlinePlayers()) {
                var resident=storage.findResident(player.getUniqueId()).orElse(null);
                if(resident!=null && resident.townId()!=null && resident.townId()==pending.town())
                    player.sendMessage(Component.text("Питание здания: "+status+". Уровень "+pending.after().level()+", прогресс "+pending.after().count()+". Получено: "+(pending.townCoins()+pending.civCoins())+" монет, "+pending.culture()+" культуры."));
            }
        }
    }
    /** Explicit isolated-test flags hold a pending operation until restart. */
    private void interrupt(String phase,long build) {
        if(build==Long.getLong("civcraft.test.consumption.build",-1)
                && phase.equals(System.getProperty("civcraft.test.consumption.interruption"))) {
            testingPaused=true;throw new IllegalStateException("Injected building meal interruption at "+phase);
        }
    }
    private boolean locked(Block block){return locked.contains(new ConsumptionContainers.Position(block.getWorld().getName(),block.getX(),block.getY(),block.getZ()));}
    private boolean locked(Inventory inventory){return locked(inventory.getHolder());}
    private boolean locked(InventoryHolder holder) {
        if(holder instanceof Chest chest)return locked(chest.getBlock());
        if(holder instanceof DoubleChest chest)return locked(chest.getLeftSide()) || locked(chest.getRightSide());
        return false;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void open(InventoryOpenEvent e){if(locked(e.getInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void click(InventoryClickEvent e){if(locked(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drag(InventoryDragEvent e){if(locked(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void move(InventoryMoveItemEvent e){if(locked(e.getSource()) || locked(e.getDestination()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void burn(BlockBurnEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(EntityExplodeEvent e){if(e.blockList().stream().anyMatch(this::locked))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(BlockExplodeEvent e){if(e.blockList().stream().anyMatch(this::locked))e.setCancelled(true);}
}
