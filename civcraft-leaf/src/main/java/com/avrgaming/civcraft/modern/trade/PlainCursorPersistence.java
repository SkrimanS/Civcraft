package com.avrgaming.civcraft.modern.trade;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import java.util.*;
import java.util.function.Predicate;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;

/** Persist the otherwise ephemeral ordinary cursor alongside the same player inventory snapshot. */
final class PlainCursorPersistence implements Listener {
    private final CivCraftModernPlugin plugin;
    private final Predicate<ItemStack> good;
    private final Predicate<UUID> frozen;
    private final NamespacedKey payload,delivery;
    private final Set<UUID> watched=new HashSet<>(),scheduled=new HashSet<>();
    private final Map<UUID,Set<World>> worlds=new HashMap<>();
    PlainCursorPersistence(CivCraftModernPlugin plugin,Predicate<ItemStack> good,Predicate<UUID> frozen){
        this.plugin=plugin;this.good=good;this.frozen=frozen;payload=new NamespacedKey(plugin,"ordinary-chest-cursor");delivery=new NamespacedKey(plugin,"ordinary-cursor-delivery");
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    private boolean ordinary(ItemStack item){return !GoodieChestPlan.empty(item) && !good.test(item);}
    private boolean has(Player player){return player.getPersistentDataContainer().has(payload,PersistentDataType.BYTE_ARRAY);}
    private ItemStack decode(byte[] bytes){
        var items=ItemStack.deserializeItemsFromBytes(bytes);if(items.length!=1 || !ordinary(items[0]))throw new IllegalStateException("Invalid ordinary cursor snapshot");return items[0];
    }
    /** Call before saveData, after setting the new inventory and cursor. */
    void applied(Player player,String receipt){
        capture(player);player.getPersistentDataContainer().set(delivery,PersistentDataType.STRING,receipt);
    }
    boolean delivered(Player player,String receipt){return receipt.equals(player.getPersistentDataContainer().get(delivery,PersistentDataType.STRING));}
    void deliver(Player player,String receipt,byte[] bytes){
        player.setItemOnCursor(decode(bytes));applied(player,receipt);player.saveData();
    }
    void clear(Player player,String receipt){
        player.getPersistentDataContainer().remove(payload);watched.remove(player.getUniqueId());player.getPersistentDataContainer().set(delivery,PersistentDataType.STRING,receipt);player.saveData();
    }
    void beforeSave(Player player){if(watched.contains(player.getUniqueId()))capture(player);}
    private void capture(Player player){
        var item=player.getItemOnCursor();
        if(ordinary(item)){player.getPersistentDataContainer().set(payload,PersistentDataType.BYTE_ARRAY,ItemStack.serializeItemsAsBytes(new ItemStack[]{item}));watched.add(player.getUniqueId());}
        else {player.getPersistentDataContainer().remove(payload);watched.remove(player.getUniqueId());}
    }
    /** Login/reload only: live inventory events update this snapshot, never replay it. */
    void restore(Player player){
        if(watched.contains(player.getUniqueId()))return;
        byte[] bytes=player.getPersistentDataContainer().get(payload,PersistentDataType.BYTE_ARRAY);if(bytes==null)return;
        if(GoodieChestPlan.empty(player.getItemOnCursor()))player.setItemOnCursor(decode(bytes));capture(player);player.saveData();
    }
    private void changed(Player player,Inventory inventory){
        if(!watched.contains(player.getUniqueId()))return;
        if(inventory!=null){var location=inventory.getLocation();if(location!=null)worlds.computeIfAbsent(player.getUniqueId(),id->new HashSet<>()).add(location.getWorld());}
        if(!scheduled.add(player.getUniqueId()))return;
        plugin.getServer().getScheduler().runTask(plugin,()->{
            scheduled.remove(player.getUniqueId());var affected=worlds.remove(player.getUniqueId());
            if(!player.isOnline() || player.isDead() || frozen.test(player.getUniqueId()))return;
            if(affected!=null)for(var world:affected)world.save(true);
            capture(player);player.saveData();
        });
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void click(InventoryClickEvent event){if(event.getWhoClicked() instanceof Player player){if(watched.contains(player.getUniqueId()) && event.getAction().name().startsWith("DROP_"))worlds.computeIfAbsent(player.getUniqueId(),id->new HashSet<>()).add(player.getWorld());changed(player,event.getView().getTopInventory());}}
    @EventHandler(priority=EventPriority.MONITOR)
    public void drag(InventoryDragEvent event){if(event.getWhoClicked() instanceof Player player)changed(player,event.getView().getTopInventory());}
    @EventHandler(priority=EventPriority.MONITOR)
    public void close(InventoryCloseEvent event){if(event.getPlayer() instanceof Player player){if(watched.contains(player.getUniqueId()))worlds.computeIfAbsent(player.getUniqueId(),id->new HashSet<>()).add(player.getWorld());changed(player,event.getInventory());}}
    private void stash(Player player){
        if(frozen.test(player.getUniqueId()) || !watched.contains(player.getUniqueId()) && !has(player))return;
        if(!watched.contains(player.getUniqueId()) && has(player)){player.saveData();return;}
        capture(player);
        // Prevent vanilla's later container close from returning a second copy to the inventory.
        if(ordinary(player.getItemOnCursor()))player.setItemOnCursor(null);
        player.saveData();watched.remove(player.getUniqueId());worlds.remove(player.getUniqueId());
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void quit(PlayerQuitEvent event){stash(event.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST)
    public void death(PlayerDeathEvent event){
        var player=event.getEntity();if(!watched.contains(player.getUniqueId()) && !has(player))return;
        if(event.getKeepInventory()){stash(player);return;}
        if(!watched.contains(player.getUniqueId()) && has(player) && GoodieChestPlan.empty(player.getItemOnCursor()))event.getDrops().add(decode(player.getPersistentDataContainer().get(payload,PersistentDataType.BYTE_ARRAY)));
        if(ordinary(player.getItemOnCursor())){event.getDrops().add(player.getItemOnCursor().clone());player.setItemOnCursor(null);}
        player.getPersistentDataContainer().remove(payload);watched.remove(player.getUniqueId());worlds.remove(player.getUniqueId());
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void respawn(PlayerRespawnEvent event){plugin.getServer().getScheduler().runTask(plugin,()->{if(event.getPlayer().isOnline() && !frozen.test(event.getPlayer().getUniqueId()))restore(event.getPlayer());});}
    void shutdown(){for(var player:Bukkit.getOnlinePlayers())stash(player);}
}
