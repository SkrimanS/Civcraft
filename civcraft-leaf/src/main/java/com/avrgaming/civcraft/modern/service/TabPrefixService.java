package com.avrgaming.civcraft.modern.service;

import com.avrgaming.civcraft.modern.domain.CampRecord;
import java.sql.SQLException;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class TabPrefixService {
    private final JavaPlugin plugin;
    private final CivCraftGameService game;

    public TabPrefixService(JavaPlugin plugin, CivCraftGameService game) {
        this.plugin = plugin;
        this.game = game;
    }

    public void update(Player player) {
        try {
            game.camp(game.resident(player)).ifPresentOrElse(
                    camp -> player.setPlayerListName(prefix(camp) + player.getName()),
                    () -> player.setPlayerListName(player.getName())
            );
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to update CivCraft tab prefix for " + player.getName() + ": " + exception.getMessage());
        }
    }

    private String prefix(CampRecord camp) {
        String clean = camp.name().replaceAll("[^A-Za-zА-Яа-я0-9]", "").toUpperCase();
        String five = clean.length() <= 5 ? clean : clean.substring(0, 5);
        return "[" + five + "] ";
    }
}
