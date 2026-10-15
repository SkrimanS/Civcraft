package com.avrgaming.civcraft.modern.town;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.io.File;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

public final class TownUpgradeService {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final TownCatalog catalog;
    private final int dailyHour;
    private final ZoneId zone;
    public TownUpgradeService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;
        catalog=new TownCatalog(new File(plugin.getDataFolder(),"civcraft/data/town.yml"));
        dailyHour=Integer.getInteger("civcraft.test.daily.hour",YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/civ.yml")).getInt("global.daily_upkeep_hour",20));
        if(dailyHour<0 || dailyHour>23)throw new IllegalArgumentException("Invalid daily economy hour");
        zone=ZoneId.of(plugin.getConfig().getString("server.timezone",ZoneId.systemDefault().getId()));
        storage.query(db->{UpgradeStore.install(db);BankDailyStore.install(db);return null;});
    }
    public TownCatalog.Level level(int number){return catalog.level(number);}
    public TownCatalog catalog(){return catalog;}
    public void resetLibrary(Player player)throws SQLException {
        var resident=storage.findResident(player.getUniqueId()).orElseThrow();
        if(resident.townId()==null)throw new IllegalArgumentException("Вы не состоите в городе.");
        var ids=catalog.upgrades().stream().filter(u->u.action().equals("enable_library_enchantment")).map(UpgradeDefinition::id).toList();
        storage.transaction(db->{com.avrgaming.civcraft.modern.trade.LibraryStore.reset(db,resident.townId(),player.getUniqueId(),ids);return null;});
        refreshBanks(resident.townId());
        player.sendMessage(Component.text("Исследования библиотеки сброшены. Четыре места освобождены; уровень сохранён. Монеты за прежние исследования не возвращаются."));
    }
    public List<String> ids(){return catalog.upgrades().stream().filter(UpgradeDefinition::supported).map(UpgradeDefinition::id).toList();}
    public void command(Player player,String[] args)throws SQLException {
        var resident=storage.findResident(player.getUniqueId()).orElseThrow();
        if(resident.townId()==null)throw new IllegalArgumentException("Вы не состоите в городе.");
        var town=storage.findTown(resident.townId()).orElseThrow();
        String action=args.length>2?args[2].toLowerCase(Locale.ROOT):"list";
        var owned=storage.query(db->UpgradeStore.purchased(db,town.id()));
        if(action.equals("buy")) {
            if(args.length<4)throw new IllegalArgumentException("Использование: /town upgrade buy <улучшение>");
            var upgrade=catalog.require(String.join(" ",Arrays.copyOfRange(args,3,args.length)));
            if(upgrade.action().equals("set_tradeship_upgrade_level") && plugin.tradeShips()!=null)
                plugin.tradeShips().requireTownUnlocked(town.id());
            storage.transaction(db->{UpgradeStore.buy(db,town.id(),player.getUniqueId(),upgrade,System.currentTimeMillis());return null;});
            player.sendMessage(Component.text("Улучшение куплено: "+upgrade.name()+". Из казны списано "+upgrade.cost()+" монет."));
            refreshBanks(town.id());
        } else if(action.equals("purchased")) {
            for(var definition:catalog.upgrades())if(owned.contains(definition.id()))player.sendMessage(Component.text(definition.id()+" — "+definition.name()));
            if(owned.isEmpty())player.sendMessage(Component.text("В городе ещё нет купленных улучшений."));
        } else if(action.equals("list")) {
            String category=args.length>3?args[3]:"all";
            for(var definition:catalog.upgrades()) {
                if(!definition.supported() || owned.contains(definition.id()))continue;
                if(!category.equalsIgnoreCase("all") && !category.equalsIgnoreCase(definition.category()))continue;
                player.sendMessage(Component.text(definition.id()+" — "+definition.name()+", "+definition.cost()+" монет"));
            }
        } else throw new IllegalArgumentException("Команды: /town upgrade list, purchased, buy <улучшение>");
    }
    public void tick() {
        var now=ZonedDateTime.now(zone);
        if(now.getHour()<dailyHour)return;
        try {
            record DailyResult(Map<Long,Long> interest,List<TaxStore.Notice> notices,List<com.avrgaming.civcraft.modern.wonder.WonderIncomeStore.Credit> bonuses) {}
            var result=storage.transaction(db->{
                TaxStore.collect(db,now.toLocalDate().toString(),System.currentTimeMillis());
                var upkeep=plugin.upkeep().daily(db,now.toLocalDate().toString(),System.currentTimeMillis());
                var notices=TaxStore.advanceGrace(db,now.toLocalDate().toString(),System.currentTimeMillis());
                return new DailyResult(BankDailyStore.interest(db,now.toLocalDate().toString(),System.currentTimeMillis()),notices,upkeep.bonuses());
            });
            result.notices().forEach(plugin.taxes()::notify);
            for(var bonus:result.bonuses())for(var player:plugin.getServer().getOnlinePlayers()) {
                var resident=storage.findResident(player.getUniqueId()).orElse(null);
                if(bonus.type().equals("w_notre_dame")){if(resident!=null&&resident.townId()!=null&&resident.townId()==bonus.town())player.sendMessage(Component.text("Нотр-Дам: мирных городов — "+bonus.units()+". Доход до налога — "+bonus.coins()+" монет."));continue;}
                if(resident!=null&&resident.civId()!=null&&resident.civId()==bonus.civ())player.sendMessage(Component.text((bonus.type().equals("w_colossus")?"Колосс: культурных участков — ":"Колизей: городов в мире — ")+bonus.units()+". Начислено цивилизации "+bonus.coins()+" монет."));
            }
            for(var payment:result.interest().entrySet()) {
                var town=storage.findTown(payment.getKey()).orElse(null);
                if(town==null)continue;
                for(var player:plugin.getServer().getOnlinePlayers()) {
                    var resident=storage.findResident(player.getUniqueId()).orElse(null);
                    if(resident!=null && resident.townId()!=null && resident.townId().equals(town.id()))player.sendMessage(Component.text("Банк города "+town.name()+" начислил "+payment.getValue()+" монет процентов."));
                }
            }
        } catch(SQLException exception){plugin.getLogger().warning("Daily upkeep and bank interest failed: "+exception.getMessage());}
    }
    private void refreshBanks(long town)throws SQLException {
        var ids=storage.query(db->{
            List<Long> result=new ArrayList<>();
            try(var query=db.prepareStatement("SELECT id FROM structure_builds WHERE town_id=? AND structure_id IN ('s_bank','s_grocer','s_library','ti_fish_hatchery') AND status='COMPLETE'")) {
                query.setLong(1,town);try(var rows=query.executeQuery()){while(rows.next())result.add(rows.getLong(1));}
            }
            return result;
        });
        ids.forEach(id->plugin.trades().refreshBuild(id));
    }
}
