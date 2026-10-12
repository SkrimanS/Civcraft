package com.avrgaming.civcraft.modern.campnpc.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class GuiHolder implements InventoryHolder {
    private final GuiType type;
    private final GuiContext context;

    public GuiHolder(GuiType type, GuiContext context) {
        this.type = type;
        this.context = context;
    }

    public GuiType type() {
        return type;
    }

    public GuiContext context() {
        return context;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
