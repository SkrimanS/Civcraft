package com.avrgaming.civcraft.modern.service;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.domain.TownClaimRecord;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;

public final class TownClaimProtectionListener implements Listener {
    private final CivCraftModernPlugin plugin;
    private final TownClaimService claims;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public TownClaimProtectionListener(CivCraftModernPlugin plugin, TownClaimService claims) {
        this.plugin = plugin;
        this.claims = claims;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        protect(event.getBlock().getLocation(), event.getPlayer(), () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        protect(event.getBlock().getLocation(), event.getPlayer(), () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null) {
            return;
        }
        if (plugin.getConfig().getBoolean("claim-protection.prevent-foreign-interactions", true)) {
            protect(event.getClickedBlock().getLocation(), event.getPlayer(),
                    () -> event.setCancelled(true));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || !plugin.getConfig().getBoolean("claim-protection.prevent-foreign-interactions", true)) {
            return;
        }
        Location location = event.getInventory().getLocation();
        if (location != null) {
            protect(location, player, () -> event.setCancelled(true));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block affected = event.getBlockClicked().getRelative(event.getBlockFace());
        protect(affected.getLocation(), event.getPlayer(), () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        protect(event.getBlockClicked().getLocation(), event.getPlayer(),
                () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (isClaimed(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (plugin.getConfig().getBoolean("claim-protection.prevent-environment-damage", true)
                && isClaimed(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFluidSpread(BlockFromToEvent event) {
        if (plugin.getConfig().getBoolean("claim-protection.prevent-environment-damage", true)
                && isClaimed(event.getToBlock().getLocation())
                && !sameClaim(event.getBlock().getLocation(), event.getToBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (plugin.getConfig().getBoolean("claim-protection.prevent-environment-damage", true)) {
            if (isClaimed(event.getBlock().getLocation())) {
                event.setCancelled(true);
                return;
            }
            for (Block moved : event.getBlocks()) {
                if (isClaimed(moved.getLocation())
                        || isClaimed(moved.getRelative(event.getDirection()).getLocation())) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (plugin.getConfig().getBoolean("claim-protection.prevent-environment-damage", true)) {
            if (isClaimed(event.getBlock().getLocation())) {
                event.setCancelled(true);
                return;
            }
            for (Block moved : event.getBlocks()) {
                if (isClaimed(moved.getLocation())
                        || isClaimed(moved.getRelative(event.getDirection().getOppositeFace()).getLocation())) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (plugin.getConfig().getBoolean("claim-protection.prevent-explosion-damage", true)) {
            removeClaimedExplosionBlocks(event.blockList());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (plugin.getConfig().getBoolean("claim-protection.prevent-explosion-damage", true)) {
            removeClaimedExplosionBlocks(event.blockList());
        }
    }

    private void removeClaimedExplosionBlocks(List<Block> blocks) {
        blocks.removeIf(block -> isClaimed(block.getLocation()));
    }

    private boolean sameClaim(Location source, Location destination) {
        try {
            Optional<TownClaimRecord> from = claims.claimAt(source);
            Optional<TownClaimRecord> to = claims.claimAt(destination);
            return from.isPresent() && to.isPresent()
                    && from.get().townId() == to.get().townId();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to check fluid claim boundary: " + exception.getMessage());
            return false;
        }
    }

    /** Fail closed: when SQLite has an error, do not permit destruction. */
    private boolean isClaimed(Location location) {
        try {
            return claims.claimAt(location).isPresent();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to check town protection: " + exception.getMessage());
            return true;
        }
    }

    private void protect(Location location, Player player, Runnable cancel) {
        try {
            Optional<TownClaimRecord> claim = claims.claimAt(location);
            if (claim.isPresent() && !claims.canBuild(player, claim.get())) {
                cancel.run();
                player.sendMessage(miniMessage.deserialize(
                        "<red>Эта территория принадлежит другому городу.</red>"));
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to check town protection: " + exception.getMessage());
            cancel.run();
        }
    }
}
