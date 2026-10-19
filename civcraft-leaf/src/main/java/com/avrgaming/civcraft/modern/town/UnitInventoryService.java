package com.avrgaming.civcraft.modern.town;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.trade.GoodieTransferService;
import java.sql.SQLException;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Native inventory guards for UnitMaterial and its temporary mission books. */
public final class UnitInventoryService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final NamespacedKey ownerTown;
    private final Map<UUID,Long> warnings=new HashMap<>();
    private record Relocation(ItemStack item,boolean groundPickup){}
    private final Map<UUID,Relocation> reposition=new LinkedHashMap<>();
    private final Set<UUID> closeChecks=new LinkedHashSet<>();
    private long lastFailure;
    public UnitInventoryService(CivCraftModernPlugin plugin,StorageBootstrap storage) {
        this.plugin=plugin;this.storage=storage;ownerTown=new NamespacedKey(plugin,"unit-town");
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::finishMoves,1L,1L);
    }
    private boolean unit(ItemStack item){String id=plugin.nativeItems().id(item);return "u_spy".equals(id)||"u_settler".equals(id);}
    private boolean book(ItemStack item){String id=plugin.nativeItems().id(item);return id!=null && id.startsWith("spy_");}
    private int amount(ItemStack item){return unit(item)?item.getAmount():0;}
    private int carried(Player player){int total=0;for(var item:player.getInventory().getContents())total+=amount(item);return total;}
    private void owner(Player player,ItemStack item)throws SQLException {
        if(!unit(item))return;
        Long town=item.getItemMeta().getPersistentDataContainer().get(ownerTown,PersistentDataType.LONG);
        if(town==null)return; // Legacy unit items without an owning town are unrestricted.
        var origin=storage.findTown(town).orElse(null);if(origin==null)return;
        var resident=storage.findResident(player.getUniqueId()).orElse(null);
        if(resident==null || resident.civId()==null || resident.civId()!=origin.civId())throw new IllegalArgumentException("Этот юнит принадлежит другой цивилизации.");
    }
    private void incoming(Player player,ItemStack item,int removed)throws SQLException {
        if(!unit(item))return;owner(player,item);
        if(carried(player)-removed+amount(item)>1)throw new IllegalArgumentException("Можно носить только один юнит. Уберите текущий в сундук или выбросьте.");
    }
    private void message(Player player,String text) {
        long now=System.currentTimeMillis();if(now-warnings.getOrDefault(player.getUniqueId(),0L)<2000)return;
        warnings.put(player.getUniqueId(),now);player.sendMessage(Component.text(text));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void click(InventoryClickEvent event) {
        if(!(event.getWhoClicked() instanceof Player player))return;
        var current=event.getCurrentItem();var cursor=event.getCursor();boolean bottom=event.getClickedInventory()==player.getInventory();
        try {
            if((event.getAction()==InventoryAction.DROP_ALL_CURSOR || event.getAction()==InventoryAction.DROP_ONE_CURSOR) && book(cursor)
                    || (event.getAction()==InventoryAction.DROP_ALL_SLOT || event.getAction()==InventoryAction.DROP_ONE_SLOT) && book(current))throw new IllegalArgumentException("Книги миссий нельзя выбрасывать.");
            if(event.getAction()==InventoryAction.COLLECT_TO_CURSOR && unit(cursor))throw new IllegalArgumentException("Юниты не объединяются в стопку.");
            if(event.isShiftClick() && unit(current)) {
                if(!bottom)incoming(player,current,0);
                else if(event.getView().getType()==InventoryType.CRAFTING || event.getView().getType()==InventoryType.CREATIVE)event.setCancelled(true);
            } else if(event.getAction()==InventoryAction.HOTBAR_SWAP || event.getAction()==InventoryAction.HOTBAR_MOVE_AND_READD) {
                ItemStack hotbar=event.getClick()==ClickType.SWAP_OFFHAND?player.getInventory().getItemInOffHand():event.getHotbarButton()<0?null:player.getInventory().getItem(event.getHotbarButton());
                if(!bottom)incoming(player,current,amount(hotbar));
            } else if(bottom && unit(cursor) && Set.of(InventoryAction.PLACE_ALL,InventoryAction.PLACE_ONE,InventoryAction.PLACE_SOME,InventoryAction.SWAP_WITH_CURSOR).contains(event.getAction()))incoming(player,cursor,amount(current));
        }catch(IllegalArgumentException failure){event.setCancelled(true);message(player,failure.getMessage());}
        catch(SQLException failure){event.setCancelled(true);plugin.getLogger().warning("Unit inventory ownership check failed: "+failure.getMessage());}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void drag(InventoryDragEvent event) {
        if(!(event.getWhoClicked() instanceof Player player) || !unit(event.getOldCursor()))return;
        try {
            int count=carried(player);boolean enters=false;
            for(var entry:event.getNewItems().entrySet())if(event.getView().getInventory(entry.getKey())==player.getInventory()) {
                enters=true;count-=amount(event.getView().getItem(entry.getKey()));count+=amount(entry.getValue());
            }
            if(enters){owner(player,event.getOldCursor());if(count>1)throw new IllegalArgumentException("Можно носить только один юнит.");}
        }catch(IllegalArgumentException failure){event.setCancelled(true);message(player,failure.getMessage());}
        catch(SQLException failure){event.setCancelled(true);plugin.getLogger().warning("Unit drag ownership check failed: "+failure.getMessage());}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void pickup(EntityPickupItemEvent event) {
        var item=event.getItem().getItemStack();
        if(book(item)){event.setCancelled(true);event.getItem().remove();return;}
        if(!(event.getEntity() instanceof Player player) || !unit(item))return;
        try{incoming(player,item,0);}
        catch(IllegalArgumentException failure){event.setCancelled(true);message(player,failure.getMessage());}
        catch(SQLException failure){event.setCancelled(true);plugin.getLogger().warning("Unit pickup ownership check failed: "+failure.getMessage());}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void drop(PlayerDropItemEvent event){if(book(event.getItemDrop().getItemStack())){event.setCancelled(true);message(event.getPlayer(),"Книги миссий нельзя выбрасывать.");}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void spawn(ItemSpawnEvent event){if(book(event.getEntity().getItemStack()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void entity(PlayerInteractEntityEvent event){var item=event.getPlayer().getInventory().getItem(event.getHand());if(book(item))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void craft(CraftItemEvent event){if(Arrays.stream(event.getInventory().getMatrix()).anyMatch(item->unit(item)||book(item)))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)
    public void death(org.bukkit.event.entity.PlayerDeathEvent event) {
        if(event.getKeepInventory())return;
        var soulbound=new NamespacedKey(plugin,"soulbound");
        java.util.function.Predicate<ItemStack> removed=item->book(item) || unit(item) && !item.getItemMeta().getPersistentDataContainer().has(soulbound,PersistentDataType.BYTE);
        event.getDrops().removeIf(removed);event.getItemsToKeep().removeIf(removed);
    }
    private void reposition(Player player,ItemStack item){if(unit(item))reposition.put(player.getUniqueId(),new Relocation(item.clone(),false));}
    /** Wait for Bukkit to apply the accepted move; never mutate an inventory inside its click. */
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void acceptedClick(InventoryClickEvent event) {
        if(!(event.getWhoClicked() instanceof Player player) || event.isShiftClick())return;
        boolean bottom=event.getClickedInventory()==player.getInventory();
        switch(event.getAction()) {
            case PLACE_ALL,PLACE_ONE,PLACE_SOME,SWAP_WITH_CURSOR -> {if(bottom)reposition(player,event.getCursor());}
            case HOTBAR_SWAP,HOTBAR_MOVE_AND_READD -> {
                // Numeric keys and the offhand key use the same native swap operation.
                if(!bottom)reposition(player,event.getCurrentItem());
            }
            default -> {}
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void acceptedDrag(InventoryDragEvent event) {
        if(event.getWhoClicked() instanceof Player player && event.getNewItems().keySet().stream().anyMatch(slot->event.getView().getInventory(slot)==player.getInventory()))reposition(player,event.getOldCursor());
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void acceptedPickup(EntityPickupItemEvent event){if(event.getEntity() instanceof Player player && unit(event.getItem().getItemStack()))reposition.put(player.getUniqueId(),new Relocation(event.getItem().getItemStack().clone(),true));}
    @EventHandler(priority=EventPriority.MONITOR)
    public void closed(InventoryCloseEvent event){if(event.getPlayer() instanceof Player player)closeChecks.add(player.getUniqueId());}
    private void finishMoves() {
        var waiting=new LinkedHashSet<>(closeChecks);waiting.addAll(reposition.keySet());int budget=16;
        for(var uuid:waiting) {
            if(budget--<=0)break;
            var player=plugin.getServer().getPlayer(uuid);
            if(player==null || player.isDead()){closeChecks.remove(uuid);reposition.remove(uuid);continue;}
            var target=reposition.get(uuid);boolean clean=closeChecks.contains(uuid);
            try {
                plugin.goodieTransfers().changeUnitInventory(player,(inventory,cursor)->{
                    var drops=new ArrayList<ItemStack>();
                    if(clean)drops.addAll(UnitInventoryLayout.keepOneUnit(inventory,this::unit));
                    if(target!=null)drops.addAll(UnitInventoryLayout.reposition(inventory,target.item(),target.groundPickup()));
                    return new GoodieTransferService.InventoryChange(inventory,cursor,drops);
                });
                closeChecks.remove(uuid);reposition.remove(uuid);
            }catch(IllegalArgumentException waitingForJournal){/* Retry after the other inventory intent completes. */}
            catch(SQLException|RuntimeException failure){long now=System.currentTimeMillis();if(now-lastFailure>30000){lastFailure=now;plugin.getLogger().severe("Unit slot restoration retained: "+failure.getMessage());}}
        }
    }
    @EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent event){var uuid=event.getPlayer().getUniqueId();warnings.remove(uuid);closeChecks.remove(uuid);reposition.remove(uuid);}
}
