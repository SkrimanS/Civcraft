package com.avrgaming.civcraft.modern.build;

import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.SQLException;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Prevent world/inventory edits during an active construction or recovery.
 *
 * Chunk footprints are locked in SQLite for QUEUED, BUILDING, rollback and
 * demolition. Locking even authorized residents is intentional: placing a chest,
 * emptying an inventory or breaking blocks while the terrain journal is being
 * restored risks losing items, duping resources or replaying stale block states.
 * Staff can use the CivCraft build cancellation and demolition commands instead.
 */
public final class ConstructionAreaProtectionListener implements Listener {
    private final JavaPlugin plugin;
    private final StorageBootstrap storage;

    public ConstructionAreaProtectionListener(JavaPlugin plugin, StorageBootstrap storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    private boolean locked(Location location) {
        if (!plugin.getConfig().getBoolean("structures.lock-active-worksites", true)
                || location == null || location.getWorld() == null) {
            return false;
        }
        try {
            return storage.isActiveStructureChunk(location.getWorld().getName(),
                    location.getBlockX() >> 4, location.getBlockZ() >> 4);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to check construction footprint: " + exception.getMessage());
            // When persistence is unavailable, do not allow destructive changes.
            return true;
        }
    }

    private void reject(Player player) {
        player.sendMessage("§cЧанк временно заблокирован: строительство или восстановление CivCraft.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (locked(event.getBlock().getLocation())) {
            event.setCancelled(true);
            reject(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (locked(event.getBlockPlaced().getLocation())) {
            event.setCancelled(true);
            reject(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.HAND
                && event.getAction() == Action.RIGHT_CLICK_BLOCK
                && event.getClickedBlock() != null && locked(event.getClickedBlock().getLocation())) {
            event.setCancelled(true);
            reject(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        Location location = event.getInventory().getLocation();
        if (locked(location)) {
            event.setCancelled(true);
            if (event.getPlayer() instanceof Player player) reject(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (locked(event.getSource().getLocation()) || locked(event.getDestination().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (locked(event.getBlockClicked().getRelative(event.getBlockFace()).getLocation())) {
            event.setCancelled(true);
            reject(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (locked(event.getBlockClicked().getLocation())) {
            event.setCancelled(true);
            reject(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFluid(BlockFromToEvent event) {
        if (locked(event.getToBlock().getLocation()) || locked(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (locked(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (locked(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (locked(event.getBlock().getLocation())) {
            event.setCancelled(true);
            return;
        }
        for (Block block : event.getBlocks()) {
            if (locked(block.getLocation()) || locked(block.getRelative(event.getDirection()).getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (locked(event.getBlock().getLocation())) {
            event.setCancelled(true);
            return;
        }
        for (Block block : event.getBlocks()) {
            if (locked(block.getLocation())
                    || locked(block.getRelative(event.getDirection().getOppositeFace()).getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        if(event.blockList().stream().anyMatch(block -> locked(block.getLocation())))event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        if(event.blockList().stream().anyMatch(block -> locked(block.getLocation())))event.setCancelled(true);
    }
}
