package com.avrgaming.civcraft.modern.town;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
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
    public UnitInventoryService(CivCraftModernPlugin plugin,StorageBootstrap storage) {
        this.plugin=plugin;this.storage=storage;ownerTown=new NamespacedKey(plugin,"unit-town");
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
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
    @EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent event){warnings.remove(event.getPlayer().getUniqueId());}
}
