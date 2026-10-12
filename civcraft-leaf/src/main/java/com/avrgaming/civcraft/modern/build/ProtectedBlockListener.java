package com.avrgaming.civcraft.modern.build;

import com.avrgaming.civcraft.modern.campnpc.lang.Lang;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.SQLException;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class ProtectedBlockListener implements Listener {
    private final JavaPlugin plugin;
    private final StorageBootstrap storage;
    private final Lang lang;

    public ProtectedBlockListener(JavaPlugin plugin, StorageBootstrap storage) {
        this(plugin, storage, new Lang(plugin));
    }

    public ProtectedBlockListener(JavaPlugin plugin, StorageBootstrap storage, Lang lang) {
        this.plugin = plugin;
        this.storage = storage;
        this.lang = lang;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        try {
            if (storage.isProtectedBlock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ())) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(lang.component("protected-block.break", "&cЭтот блок является структурным блоком CivCraft-постройки."));
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to check protected block: " + exception.getMessage());
        }
    }
}
