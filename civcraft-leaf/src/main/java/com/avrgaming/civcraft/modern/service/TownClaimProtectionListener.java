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
import org.bukkit.event.inventory.InventoryMoveItemEvent;
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
        protect(event.getBlock().getLocation(), event.getPlayer(), "destroy", () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        protect(event.getBlock().getLocation(), event.getPlayer(), "build", () -> event.setCancelled(true));
        if(event instanceof org.bukkit.event.block.BlockMultiPlaceEvent multiple)
            for(var state:multiple.getReplacedBlockStates())protect(state.getLocation(),event.getPlayer(),"build",()->event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if ((event.getHand() != EquipmentSlot.HAND && event.getAction()!=Action.PHYSICAL)
                || (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction()!=Action.PHYSICAL)
                || event.getClickedBlock() == null) {
            return;
        }
        if(plugin.trades()!=null && plugin.trades().isPublicTradePoint(event.getClickedBlock()))return;
        if(plugin.warRespawns()!=null && plugin.warRespawns().isRespawnSign(event.getClickedBlock()))return;
        if (plugin.getConfig().getBoolean("claim-protection.prevent-foreign-interactions", true)) {
            protect(event.getClickedBlock().getLocation(), event.getPlayer(), "interact",
                    () -> event.setCancelled(true));
            if(event.hasItem() && (event.getMaterial().name().endsWith("SPAWN_EGG") || List.of("FLINT_AND_STEEL","FIRE_CHARGE","BONE_MEAL").contains(event.getMaterial().name())))
                protect(event.getClickedBlock().getLocation(),event.getPlayer(),"itemuse",()->event.setCancelled(true));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || !plugin.getConfig().getBoolean("claim-protection.prevent-foreign-interactions", true)) {
            return;
        }
        for(Location location:inventoryLocations(event.getInventory())) {
            protect(location, player, "interact", () -> event.setCancelled(true));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (!plugin.getConfig().getBoolean("claim-protection.prevent-cross-border-hoppers", true)) {
            return;
        }
        try {
          for(Location source:inventoryLocations(event.getSource()))for(Location destination:inventoryLocations(event.getDestination())) {
            Optional<TownClaimRecord> from = claims.claimAt(source);
            Optional<TownClaimRecord> to = claims.claimAt(destination);
            if (from.isPresent() || to.isPresent()) {
                if (from.isEmpty() || to.isEmpty() || !claims.sameOwner(from.get(),to.get())) {
                    // Keep a city's automatic item transport inside its own
                    // boundaries, even when an external hopper touches a chest.
                    event.setCancelled(true);
                }
            }
          }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to check hopper claim boundary: " + exception.getMessage());
            event.setCancelled(true);
        }
    }
    private List<Location> inventoryLocations(org.bukkit.inventory.Inventory inventory) {
        if(inventory.getHolder() instanceof org.bukkit.block.DoubleChest chest) {
            var locations=new java.util.ArrayList<Location>();
            if(chest.getLeftSide() instanceof org.bukkit.block.BlockState left)locations.add(left.getLocation());
            if(chest.getRightSide() instanceof org.bukkit.block.BlockState right)locations.add(right.getLocation());
            return locations;
        }
        return inventory.getLocation()==null?List.of():List.of(inventory.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block affected = event.getBlockClicked().getRelative(event.getBlockFace());
        protect(affected.getLocation(), event.getPlayer(), "itemuse", () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        protect(event.getBlockClicked().getLocation(), event.getPlayer(), "itemuse",
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
                    && claims.sameOwner(from.get(),to.get());
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

    private void protect(Location location, Player player, String action, Runnable cancel) {
        try {
            Optional<TownClaimRecord> claim = claims.claimAt(location);
            if (claim.isPresent() && !claims.canAccess(player, claim.get(),action)) {
                cancel.run();
                player.sendMessage(miniMessage.deserialize(
                        "<red>У вас нет права на это действие на участке.</red>"));
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to check town protection: " + exception.getMessage());
            cancel.run();
        }
    }
}
