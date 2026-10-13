package com.avrgaming.civcraft.modern.town;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.io.File;
import java.sql.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

public final class UpkeepService {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final long extraTownCost;
    public UpkeepService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;
        extraTownCost=(long)YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/civ.yml")).getDouble("civ.town_upkeep",500);
        storage.query(db->{UpkeepStore.install(db);return null;});
    }
    private List<UpkeepStore.Charge> plan(Connection db)throws SQLException {
        Map<String,Double> costs=new HashMap<>();
        for(var definition:plugin.legacyStructures().listDefinitions(Integer.MAX_VALUE))costs.put(definition.id(),definition.upkeep());
        List<UpkeepStore.Charge> charges=new ArrayList<>();
        record Town(long id,long civ,int level,String government,long capital) {}
        List<Town> towns=new ArrayList<>();
        try(var rows=db.createStatement().executeQuery("""
                SELECT t.id,t.civ_id,t.level,c.government,
                COALESCE(c.capital_town_id,(SELECT MIN(first.id) FROM towns first WHERE first.civ_id=c.id))
                FROM towns t JOIN civilizations c ON c.id=t.civ_id
                """)) {
            while(rows.next())towns.add(new Town(rows.getLong(1),rows.getLong(2),rows.getInt(3),rows.getString(4),rows.getLong(5)));
        }
        for(var town:towns) {
            double total=plugin.townUpgrades().level(town.level()).upkeep();
            try(var query=db.prepareStatement("SELECT structure_id,COUNT(*) FROM structure_builds WHERE town_id=? AND status IN ('QUEUED','BUILDING','COMPLETE') GROUP BY structure_id")) {
                query.setLong(1,town.id());try(var rows=query.executeQuery()){while(rows.next())total+=costs.getOrDefault(rows.getString(1),0.0)*rows.getInt(2);}
            }
            total*=plugin.governments().upkeepRate(town.government());
            if(!Double.isFinite(total) || total<0 || total>1_000_000_000_000L)throw new SQLException("Unsupported town upkeep");
            charges.add(new UpkeepStore.Charge(town.id(),town.civ(),town.id()==town.capital(),(long)Math.floor(total)));
        }
        return charges;
    }
    public List<UpkeepStore.Payment> daily(Connection db,String day,long now)throws SQLException {
        var towns=plan(db);Map<Long,Long> civs=new LinkedHashMap<>();
        for(var town:towns)civs.merge(town.civ(),town.capital()?0:extraTownCost,Math::addExact);
        return UpkeepStore.daily(db,day,now,towns,civs);
    }
    public void command(Player player,String[] args,boolean civilization)throws SQLException {
        if(args.length>4 || (args.length>2 && !args[2].equalsIgnoreCase("pay")))
            throw new IllegalArgumentException("Команды: upkeep, upkeep pay [сумма]");
        var resident=storage.findResident(player.getUniqueId()).orElseThrow();
        Long id=civilization?resident.civId():resident.townId();
        if(id==null)throw new IllegalArgumentException(civilization?"Вы не состоите в цивилизации.":"Вы не состоите в городе.");
        String table=civilization?"civilizations":"towns";
        if(args.length>=3 && args[2].equalsIgnoreCase("pay")) {
            boolean authorized=civilization?storage.findCiv(id).orElseThrow().leaderUuid().equals(player.getUniqueId()):storage.findTown(id).orElseThrow().mayorUuid().equals(player.getUniqueId());
            if(!authorized)throw new IllegalArgumentException("Долг погашает руководитель из казны.");
            long limit=Long.MAX_VALUE;
            if(args.length>=4)try{limit=Long.parseLong(args[3]);}catch(NumberFormatException invalid){throw new IllegalArgumentException("Укажите положительную целую сумму.");}
            final long requested=limit;
            long paid=storage.transaction(db->UpkeepStore.payDebt(db,table,id,requested));
            player.sendMessage(Component.text("Погашено долга: "+paid+" монет."));return;
        }
        String info=storage.query(db->{
            long debt,days,balance;
            try(var query=db.prepareStatement("SELECT debt,days_in_debt,coins FROM "+table+" WHERE id=?")) {
                query.setLong(1,id);try(var row=query.executeQuery()){if(!row.next())throw new SQLException("Missing upkeep account");debt=row.getLong(1);days=row.getLong(2);balance=row.getLong(3);}
            }
            long cost=0;
            if(!civilization)for(var charge:plan(db))if(charge.town()==id)cost=charge.amount();
            if(civilization)for(var charge:plan(db))if(charge.civ()==id && !charge.capital())cost+=extraTownCost;
            return "Содержание: "+cost+" монет/день. Казна: "+balance+". Долг: "+debt+". Дней в долгу: "+days+".";
        });
        player.sendMessage(Component.text(info));
    }
}
