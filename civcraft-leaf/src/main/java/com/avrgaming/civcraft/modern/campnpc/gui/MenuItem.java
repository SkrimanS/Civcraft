package com.avrgaming.civcraft.modern.campnpc.gui;

import java.util.List;
import org.bukkit.inventory.ItemStack;

public record MenuItem(List<Integer> slots, ItemStack item, List<GuiAction> actions) {
}
