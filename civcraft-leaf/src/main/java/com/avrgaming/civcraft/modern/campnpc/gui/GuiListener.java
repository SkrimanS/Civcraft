package com.avrgaming.civcraft.modern.campnpc.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

public final class GuiListener implements Listener {
    private final MenuManager menus;

    public GuiListener(MenuManager menus) {
        this.menus = menus;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof GuiHolder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getInventory()) {
            return;
        }
        menus.handleClick((org.bukkit.entity.Player) event.getWhoClicked(), event.getInventory(), event.getSlot(), event.getClick());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof GuiHolder) {
            menus.cleanup((org.bukkit.entity.Player) event.getPlayer(), event.getInventory());
        }
    }
}
