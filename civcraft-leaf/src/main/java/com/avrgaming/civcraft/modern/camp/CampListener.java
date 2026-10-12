package com.avrgaming.civcraft.modern.camp;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Furnace;
import org.bukkit.block.data.type.Door;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.player.PlayerMoveEvent;

public final class CampListener implements Listener {
    private final JavaPlugin plugin;
    private final CampService camps;
    private final Map<UUID, String> lastCampGreeting = new ConcurrentHashMap<>();

    public CampListener(JavaPlugin plugin, CampService camps) {
        this.plugin = plugin;
        this.camps = camps;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        if (!camps.hasPendingDisband(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        plugin.getServer().getScheduler().runTask(plugin, () -> camps.handleDisbandChat(event.getPlayer(), message));
    }


    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() == null || to.getWorld() == null) {
            return;
        }
        if (from.getWorld().equals(to.getWorld()) && from.getBlockX() >> 4 == to.getBlockX() >> 4 && from.getBlockZ() >> 4 == to.getBlockZ() >> 4) {
            return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        String campName = camps.campNameAt(to).orElse(null);
        String previous = lastCampGreeting.get(uuid);
        if (campName == null) {
            lastCampGreeting.remove(uuid);
            return;
        }
        if (!campName.equals(previous)) {
            lastCampGreeting.put(uuid, campName);
            camps.showCampGreeting(event.getPlayer(), campName);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onControlBlockBreak(BlockBreakEvent event) {
        if (camps.handleControlBlockAttack(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        if (event.getInventory().getLocation() == null) {
            return;
        }
        Block block = event.getInventory().getLocation().getBlock();
        if (!(block.getState() instanceof Container) && !(block.getState() instanceof Furnace)) {
            return;
        }
        if (camps.isCampStructureChunk(block.getWorld().getName(), block.getChunk().getX(), block.getChunk().getZ())
                && !camps.isCampMemberAt(player.getUniqueId(), block.getWorld().getName(), block.getChunk().getX(), block.getChunk().getZ())) {
            event.setCancelled(true);
            player.sendMessage(camps.message("camp.access.containers", "Открывать сундуки и печки лагеря могут только участники лагеря."));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDoorInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        if (!(block.getBlockData() instanceof Door)) {
            return;
        }
        if (!camps.isCampProtectedBlock(block)) {
            return;
        }
        if (!camps.isCampMemberAt(event.getPlayer().getUniqueId(), block.getWorld().getName(), block.getChunk().getX(), block.getChunk().getZ())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(camps.message("camp.access.doors", "Открывать структурные двери лагеря могут только участники лагеря."));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFarmlandTrample(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block.getType() == Material.FARMLAND && camps.isCampStructureChunk(block.getWorld().getName(), block.getChunk().getX(), block.getChunk().getZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFarmlandDry(BlockFadeEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.FARMLAND && camps.isCampStructureChunk(block.getWorld().getName(), block.getChunk().getX(), block.getChunk().getZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCropGrow(BlockGrowEvent event) {
        Block block = event.getBlock();
        if (camps.isCampStructureChunk(block.getWorld().getName(), block.getChunk().getX(), block.getChunk().getZ())) {
            event.setCancelled(true);
        }
    }
}
