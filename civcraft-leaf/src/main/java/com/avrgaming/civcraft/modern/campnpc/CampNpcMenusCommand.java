package com.avrgaming.civcraft.modern.campnpc;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.List;

public final class CampNpcMenusCommand implements CommandExecutor, TabCompleter {
    private final CampNpcMenusService service;

    public CampNpcMenusCommand(CampNpcMenusService service) {
        this.service = service;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("civcraft.campnpcmenus.admin")) {
            sender.sendMessage(service.lang().msg("camp-npc.reload-no-permission", "&cНедостаточно прав."));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            service.reloadEverything();
            sender.sendMessage(service.lang().msg("camp-npc.reload", "&aCamp NPC menus перезагружены."));
            return true;
        }
        sender.sendMessage("§e/" + label + " reload");
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("civcraft.campnpcmenus.admin")) {
            return List.of("reload");
        }
        return List.of();
    }
}
