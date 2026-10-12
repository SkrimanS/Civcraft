package com.avrgaming.civcraft.modern.campnpc.item;

import java.util.Iterator;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class CivCraftItemListener implements Listener {
    private final JavaPlugin plugin;
    private final CivCraftItemService items;

    public CivCraftItemListener(JavaPlugin plugin, CivCraftItemService items) {
        this.plugin = plugin;
        this.items = items;
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (items.usesDeathDurability(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Iterator<ItemStack> iterator = event.getDrops().iterator();
        while (iterator.hasNext()) {
            ItemStack item = iterator.next();
            if (items.usesDeathDurability(item) && items.damageOnDeath(item)) {
                iterator.remove();
            }
        }
        if (event.getKeepInventory()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                ItemStack[] contents = event.getEntity().getInventory().getContents();
                damageInventory(contents);
                event.getEntity().getInventory().setContents(contents);
            }, 1L);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (items.consumeUse(item)) {
            ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
            if (items.itemId(hand).equals(items.itemId(item))) {
                hand.setAmount(Math.max(0, hand.getAmount() - 1));
                event.getPlayer().getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType().isEdible()) {
            return;
        }
        if (items.consumeUse(item)) {
            item.setAmount(Math.max(0, item.getAmount() - 1));
            event.getPlayer().getInventory().setItemInMainHand(item.getAmount() <= 0 ? null : item);
        }
    }

    private void damageInventory(ItemStack[] contents) {
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (items.usesDeathDurability(item) && items.damageOnDeath(item)) {
                contents[i] = null;
            }
        }
    }
}
