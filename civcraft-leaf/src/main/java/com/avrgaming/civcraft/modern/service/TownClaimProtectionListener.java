package com.avrgaming.civcraft.modern.service;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.domain.TownClaimRecord;
import java.sql.SQLException;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

public final class TownClaimProtectionListener implements Listener {
    private final CivCraftModernPlugin plugin;
    private final TownClaimService claims;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public TownClaimProtectionListener(CivCraftModernPlugin plugin, TownClaimService claims) {
        this.plugin = plugin;
        this.claims = claims;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        protect(event.getBlock().getLocation(), event.getPlayer(), () -> event.setCancelled(true));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        protect(event.getBlock().getLocation(), event.getPlayer(), () -> event.setCancelled(true));
    }

    private void protect(org.bukkit.Location location, org.bukkit.entity.Player player, Runnable cancel) {
        try {
            Optional<TownClaimRecord> claim = claims.claimAt(location);
            if (claim.isPresent() && !claims.canBuild(player, claim.get())) {
                cancel.run();
                player.sendMessage(miniMessage.deserialize("<red>Эта территория принадлежит другой цивилизации.</red>"));
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to validate town claim protection: " + exception.getMessage());
            cancel.run();
        }
    }
}
