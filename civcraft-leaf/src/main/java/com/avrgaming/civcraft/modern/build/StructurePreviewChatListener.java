package com.avrgaming.civcraft.modern.build;

import com.avrgaming.civcraft.modern.campnpc.lang.Lang;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Locale;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class StructurePreviewChatListener implements Listener {
    private final JavaPlugin plugin;
    private final LegacyStructureService structures;
    private final Lang lang;

    public StructurePreviewChatListener(JavaPlugin plugin, LegacyStructureService structures) {
        this(plugin, structures, new Lang(plugin));
    }

    public StructurePreviewChatListener(JavaPlugin plugin, LegacyStructureService structures, Lang lang) {
        this.plugin = plugin;
        this.structures = structures;
        this.lang = lang;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!structures.hasPreview(player) || structures.isFoundationPreview(player)) {
            return;
        }

        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim().toLowerCase(Locale.ROOT);
        if (!message.equals("yes") && !message.equals("no")) {
            plugin.getServer().getScheduler().runTask(plugin, () -> player.sendMessage(lang.component("build.preview-confirm-again", "&eВведите yes, чтобы начать строительство, или no, чтобы отменить превью.")));
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (message.equals("no")) {
                structures.cancelPreview(player);
                return;
            }
            try {
                QueuedLegacyBuild build = structures.confirmPreview(player);
                player.sendMessage(lang.component("build.started", "&aСтроительство начато. Постройка #{id}, чанковые блоки: {blocks}, молотки: {hammers}, город: {production}/час, время: {time}.",
                        "id", build.id(),
                        "blocks", build.queuedBlocks(),
                        "hammers", formatNumber(build.totalHammers()),
                        "production", formatNumber(build.hammersPerHour()),
                        "time", formatDuration(build.durationMillis())));
            } catch (IllegalArgumentException exception) {
                player.sendMessage(lang.component("build.error", "&cОшибка: {error}", "error", exception.getMessage()));
            } catch (SQLException exception) {
                plugin.getLogger().warning("CivCraft preview build failed: " + exception.getMessage());
                player.sendMessage(lang.component("build.database-error", "&cОшибка базы данных: {error}", "error", exception.getMessage()));
            } catch (IOException exception) {
                plugin.getLogger().warning("CivCraft preview template failed: " + exception.getMessage());
                player.sendMessage(lang.component("build.schematic-error", "&cОшибка схематики: {error}", "error", exception.getMessage()));
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        structures.cancelPreview(event.getPlayer(), true);
    }

    private String formatNumber(double value) {
        if (value == Math.rint(value)) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String formatDuration(long millis) {
        if (millis <= 0L) {
            return lang.msg("build.time-now", "сразу");
        }
        long totalSeconds = Math.max(1L, (long) Math.ceil(millis / 1000.0));
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return hours + "ч " + minutes + "м";
        }
        if (minutes > 0L) {
            return minutes + "м " + seconds + "с";
        }
        return seconds + "с";
    }
}
