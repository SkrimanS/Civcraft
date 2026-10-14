package com.avrgaming.civcraft.modern.trade;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.town.ConsumptionContainers;
import com.avrgaming.civcraft.modern.town.ConsumptionContainers.Position;
import com.avrgaming.civcraft.modern.town.ConsumptionContainers.Image;
import java.io.*;
import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.inventory.*;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import net.kyori.adventure.text.Component;

/** Hourly trade ship cycle with recoverable, locked physical container images. */
public final class TradeShipService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final TradeShipChestService chests;
    private final TradeShipCatalog catalog;
    private final TradeShipIncome.Rates rates;
    private final NamespacedKey applied;
    private final Set<Position> locked=new HashSet<>();
    private final long period;
    private boolean ready,paused;
    private long lastWarning;
    public TradeShipService(CivCraftModernPlugin plugin,StorageBootstrap storage,TradeShipChestService chests)throws SQLException,IOException {
        this.plugin=plugin;this.storage=storage;this.chests=chests;
        var data=new File(plugin.getDataFolder(),"civcraft/data");
        catalog=new TradeShipCatalog(new File(data,"structures.yml"));rates=TradeShipIncome.rates(data);
        applied=new NamespacedKey(plugin,"last-trade-ship-operation");
        period=Long.getLong("civcraft.test.tradeShip.hourMillis",3600000L);
        if(period<1000 || period>3600000)throw new IllegalArgumentException("Invalid trade ship period");
        storage.transaction(db->{TradeShipOperations.install(db);return null;});
        for(var operation:storage.query(TradeShipOperations::pending))lock(operation);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,40L,20L);
    }
    public void requireUnlocked(long build)throws SQLException {
        for(var operation:storage.query(TradeShipOperations::pending))if(operation.before().build()==build)
            throw new IllegalArgumentException("Торговая операция корабля ещё сохраняется. Дождитесь восстановления.");
    }
    public void requireTownUnlocked(long town)throws SQLException {
        for(var operation:storage.query(TradeShipOperations::pending))if(operation.town()==town)
            throw new IllegalArgumentException("Торговая операция корабля ещё сохраняется. Дождитесь восстановления.");
    }
    public void tick() {
        if(paused)return;
        try {
            for(var operation:storage.query(TradeShipOperations::pending))finish(operation);
            long hour=System.currentTimeMillis()/period;
            if(!ready){storage.transaction(db->{TradeShipOperations.start(db,period,hour);return null;});ready=true;}
            for(var ship:chests.ships()) {
                try {trade(ship,hour);}catch(Exception failure){warn("Ship "+ship.id()+" trade failed",failure);if(paused)return;}
            }
        }catch(Exception failure){warn("Trade ship recovery failed",failure);}
    }
    private void warn(String message,Exception failure) {
        if(System.currentTimeMillis()-lastWarning>30000){lastWarning=System.currentTimeMillis();plugin.getLogger().severe(message+": "+failure.getMessage());}
    }
    private void trade(TradeShipChestService.Ship ship,long hour)throws SQLException,IOException {
        var before=storage.transaction(db->TradeShipOperations.initialize(db,ship.id(),period,hour));
        if(before.hour()>=hour)return;
        boolean active=storage.query(db->{
            var health=com.avrgaming.civcraft.modern.war.BuildingDamageStore.health(db,ship.id());
            if(health==null || health.hitpoints()<=0)return false;
            try(var s=db.prepareStatement("SELECT 1 FROM towns t JOIN administrative_civilizations a ON a.civ_id=t.civ_id WHERE t.id=?")) {
                s.setLong(1,ship.town());try(var r=s.executeQuery()){if(r.next())return true;}
            }
            return com.avrgaming.civcraft.modern.war.HallValidationStore.state(db,ship.id()).valid();
        });
        if(!active)return;
        int upgrade=storage.query(db->TradeShipStore.upgrade(db,ship.town()));
        var sourcePositions=chests.positions(ship,false,upgrade,before.progress().level());
        var targetPositions=chests.positions(ship,true,upgrade,before.progress().level());
        var all=new LinkedHashSet<Position>(sourcePositions);all.addAll(targetPositions);
        if(all.stream().anyMatch(locked::contains))return;
        if(all.size()!=sourcePositions.size()+targetPositions.size())throw new IllegalStateException("Trade ship cargo directions overlap");
        List<Image> source=List.of(),target=List.of();
        if(!sourcePositions.isEmpty() && !targetPositions.isEmpty()) {
            try {source=ConsumptionContainers.capture(sourcePositions);target=ConsumptionContainers.capture(targetPositions);}
            catch(IllegalStateException missing){source=List.of();target=List.of();}
        }
        var stacks=new ArrayList<ItemStack>();var cargo=new ArrayList<TradeShipProgress.Cargo>();
        for(var image:source)for(var stack:image.contents()) {
            stacks.add(stack);
            var definition=stack==null?null:plugin.nativeItems().definition(stack).orElse(null);
            cargo.add(definition==null?null:new TradeShipProgress.Cargo(stack.getAmount(),plugin.nativeItems().tradeable(stack),definition.tradeValue()));
        }
        var outcome=TradeShipProgress.consume(catalog.levels(),before.progress(),upgrade,cargo);
        var removed=new HashSet<>(outcome.consumedSlots());var images=new ArrayList<Image>();
        for(int i=0;i<source.size();i++) {
            var contents=source.get(i).contents();for(int slot=0;slot<27;slot++)if(removed.contains(i*27+slot))contents[slot]=null;
            images.add(new Image(source.get(i).position(),contents));
        }
        for(int slot:outcome.consumedSlots()) {
            var stack=stacks.get(slot);var item=plugin.nativeItems().definition(stack).orElseThrow();
            for(var reward:TradeShipLoot.roll(item.id(),stack.getAmount(),catalog.chances(),java.util.concurrent.ThreadLocalRandom.current())) {
                var returned=reward.custom()?plugin.nativeItems().create(reward.item(),reward.amount()):new ItemStack(Material.valueOf(reward.item()),reward.amount());
                target=ConsumptionContainers.addBestEffort(target,returned);
            }
        }
        images.addAll(target);byte[] payload=ConsumptionContainers.encode(images);
        var operation=storage.transaction(db->{var payment=TradeShipIncomeStore.plan(db,ship.town(),outcome.money(),outcome.culture(),rates);
            return TradeShipOperations.prepare(db,UUID.randomUUID().toString(),before,hour,outcome,payment,payload);});
        lock(operation);interrupt("prepared",ship.id());finish(operation);
    }
    private void lock(TradeShipOperations.Pending operation)throws IOException {
        for(var image:ConsumptionContainers.decode(operation.containers()))locked.add(image.position());
    }
    private void finish(TradeShipOperations.Pending operation)throws IOException,SQLException {
        var images=ConsumptionContainers.decode(operation.containers());lock(operation);
        if(operation.consumed()>0) {
            for(var world:ConsumptionContainers.apply(images,operation.id(),applied))world.save(true);
            interrupt("world-saved",operation.before().build());
        }
        boolean completed=storage.transaction(db->TradeShipOperations.complete(db,operation.id()));
        for(var image:images)locked.remove(image.position());
        if(completed)for(var player:plugin.getServer().getOnlinePlayers()) {
            var resident=storage.findResident(player.getUniqueId()).orElse(null);
            if(resident!=null && resident.townId()!=null && resident.townId()==operation.town()) {
                var payment=operation.payment().payment();
                player.sendMessage(Component.text("Торговый корабль: "+switch(operation.result()){case "GROW"->"прогресс вырос";case "LEVELUP"->"уровень повышен";case "MAXED"->"предел уровня";default->"нет торгового груза";}
                        +". Уровень "+operation.after().level()+", продано "+operation.consumed()+" предметов. Доход города: "+payment.town().stripTrailingZeros().toPlainString()+", налог: "+payment.civilization().stripTrailingZeros().toPlainString()+", культура: "+payment.culture()+"."));
            }
        }
    }
    private void interrupt(String phase,long build) {
        if(build==Long.getLong("civcraft.test.tradeShip.build",-1) && phase.equals(System.getProperty("civcraft.test.tradeShip.interruption"))) {
            paused=true;throw new IllegalStateException("Injected trade ship interruption at "+phase);
        }
    }
    private boolean locked(Block block){return locked.contains(new Position(block.getWorld().getName(),block.getX(),block.getY(),block.getZ()));}
    private boolean locked(Inventory inventory){return locked(inventory.getHolder());}
    private boolean locked(InventoryHolder holder) {
        if(holder instanceof Chest chest)return locked(chest.getBlock());
        if(holder instanceof DoubleChest chest)return locked(chest.getLeftSide())||locked(chest.getRightSide());
        return false;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void open(InventoryOpenEvent e){if(locked(e.getInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void click(InventoryClickEvent e){if(locked(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drag(InventoryDragEvent e){if(locked(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void move(InventoryMoveItemEvent e){if(locked(e.getSource())||locked(e.getDestination()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void burn(BlockBurnEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(EntityExplodeEvent e){e.blockList().removeIf(this::locked);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(BlockExplodeEvent e){e.blockList().removeIf(this::locked);}
}
