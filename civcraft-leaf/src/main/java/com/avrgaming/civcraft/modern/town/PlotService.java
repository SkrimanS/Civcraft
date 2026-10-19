package com.avrgaming.civcraft.modern.town;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public final class PlotService implements CommandExecutor,TabCompleter {
    private final StorageBootstrap storage;
    private final com.avrgaming.civcraft.modern.economy.CivCraftEconomyService economy;
    public PlotService(CivCraftModernPlugin plugin,StorageBootstrap storage,com.avrgaming.civcraft.modern.economy.CivCraftEconomyService economy)throws SQLException {
        this.storage=storage;this.economy=economy;storage.transaction(db->{PlotStore.install(db);return null;});
        plugin.getCommand("plot").setExecutor(this);plugin.getCommand("plot").setTabCompleter(this);
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof Player player)){sender.sendMessage("Эта команда доступна в игре.");return true;}
        var location=player.getLocation();var key=new PlotStore.Key(location.getWorld().getName(),Math.floorDiv(location.getBlockX(),16),Math.floorDiv(location.getBlockZ(),16));
        String action=args.length==0?"info":args[0].toLowerCase(Locale.ROOT);
        try {
            if(action.equals("buy") && economy.usesExternalProvider())throw new IllegalArgumentException("Для покупки участка требуется внутренний баланс CivCraft.");
            String message=switch(action) {
                case "toggle"->{if(args.length!=2 || !args[1].equalsIgnoreCase("mobs"))throw new IllegalArgumentException("/plot toggle mobs");boolean enabled=storage.transaction(db->PlotStore.toggleMobs(db,key,player.getUniqueId()));yield "Появление опасных мобов на участке: "+(enabled?"разрешено":"запрещено")+".";}
                case "info"->storage.query(db->{var p=PlotStore.find(db,key);return "Участок "+key.x()+", "+key.z()+". Владелец: "+(p.owner()==null?"город":storage.findResident(UUID.fromString(p.owner())).map(r->r.name()).orElse("неизвестен"))+". Цена покупки для налогов: "+p.value()+". "+(p.forSale()?"Продаётся за "+p.price()+" монет.":"Не продаётся.")+" Группы: "+p.groups();});
                case "fs"->{if(args.length!=2)throw new IllegalArgumentException("/plot fs <цена>");long price;try{price=Long.parseLong(args[1]);}catch(NumberFormatException invalid){throw new IllegalArgumentException("Укажите целую неотрицательную цену.");}final long amount=price;storage.transaction(db->{PlotStore.offer(db,key,player.getUniqueId(),amount);return null;});yield "Участок выставлен на продажу за "+price+" монет.";}
                case "nfs"->{storage.transaction(db->{PlotStore.offer(db,key,player.getUniqueId(),null);return null;});yield "Участок снят с продажи.";}
                case "buy"->{long price=storage.transaction(db->PlotStore.buy(db,key,player.getUniqueId(),System.currentTimeMillis()));yield "Участок куплен за "+price+" монет. Теперь вы его владелец.";}
                case "setowner"->{if(args.length!=2)throw new IllegalArgumentException("/plot setowner <ник|none>");UUID target=args[1].equalsIgnoreCase("none")?null:storage.findResidentByName(args[1]).orElseThrow(()->new IllegalArgumentException("Житель не найден.")).uuid();storage.transaction(db->{PlotStore.owner(db,key,player.getUniqueId(),target);return null;});yield "Владелец участка изменён.";}
                case "addgroup","removegroup"->{if(args.length!=2)throw new IllegalArgumentException("/plot "+action+" <default|mayors|assistants>");storage.transaction(db->{PlotStore.group(db,key,player.getUniqueId(),args[1].toLowerCase(Locale.ROOT),action.equals("addgroup"));return null;});yield "Группы участка изменены.";}
                case "perm"->{if(args.length!=5 || !args[1].equalsIgnoreCase("set"))throw new IllegalArgumentException("/plot perm set <build|destroy|interact|itemuse> <owner|group|others> <on|off>");boolean allow=switch(args[4].toLowerCase(Locale.ROOT)){case "on","yes","1"->true;case "off","no","0"->false;default->throw new IllegalArgumentException("Укажите on или off.");};storage.transaction(db->{PlotStore.permission(db,key,player.getUniqueId(),args[2].toLowerCase(Locale.ROOT),args[3].toLowerCase(Locale.ROOT),allow);return null;});yield "Права участка изменены.";}
                default->"Команды: /plot info, fs <цена>, nfs, buy, setowner <ник|none>, addgroup, removegroup, perm set, toggle mobs";
            };
            player.sendMessage(Component.text(message));
            if(action.equals("info"))player.sendMessage(Component.text("Появление опасных мобов: "+(storage.query(db->PlotStore.mobs(db,key))?"разрешено":"запрещено")+". Изменить: /plot toggle mobs."));
        } catch(IllegalArgumentException failure){player.sendMessage(Component.text(failure.getMessage()));}
        catch(SQLException failure){player.sendMessage(Component.text("Не удалось выполнить операцию с участком."));player.getServer().getLogger().warning("Plot operation failed: "+failure.getMessage());}
        return true;
    }
    @Override public java.util.List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(args.length==1)return java.util.List.of("info","fs","nfs","buy","setowner","addgroup","removegroup","perm","toggle");
        if(args.length==2 && args[0].equalsIgnoreCase("toggle"))return java.util.List.of("mobs");
        if(args.length==2 && (args[0].equalsIgnoreCase("addgroup") || args[0].equalsIgnoreCase("removegroup")))return java.util.List.copyOf(PlotStore.GROUPS);
        if(args.length==2 && args[0].equalsIgnoreCase("perm"))return java.util.List.of("set");
        if(args.length==3 && args[0].equalsIgnoreCase("perm"))return java.util.List.copyOf(PlotStore.ACTIONS);
        if(args.length==4 && args[0].equalsIgnoreCase("perm"))return java.util.List.of("owner","group","others");
        if(args.length==5 && args[0].equalsIgnoreCase("perm"))return java.util.List.of("on","off");
        return java.util.List.of();
    }
}
