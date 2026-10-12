package com.avrgaming.civcraft.modern.campnpc.quest;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;

public final class QuestProgressListener implements Listener {
    private final QuestManager quests;
    private final MythicMobsHook mythicMobs = new MythicMobsHook();

    public QuestProgressListener(QuestManager quests) {
        this.quests = quests;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        String mythicId = mythicMobs.idOf(event.getEntity()).orElse(null);
        quests.progressMobKill(killer, mythicId, event.getEntity().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getWorld() == to.getWorld() && from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ())) {
            return;
        }
        quests.progressLocationVisit(event.getPlayer(), to);
    }
}
