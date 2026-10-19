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
    private final com.avrgaming.civcraft.modern.wonder.WonderEconomy wonders;
    private final com.avrgaming.civcraft.modern.war.WarUpkeepRules warUpkeep;
    private final com.avrgaming.civcraft.modern.war.RevolutionRules scoreRules;
    public record DailyResult(List<UpkeepStore.Payment> payments,List<com.avrgaming.civcraft.modern.wonder.WonderIncomeStore.Credit> bonuses) {}
    public record Breakdown(double territory,double structures,long total) {}
    public Breakdown report(long town)throws SQLException {
        return storage.query(db->{
            int level;try(var s=db.prepareStatement("SELECT level FROM towns WHERE id=?")){s.setLong(1,town);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Город не найден.");level=r.getInt(1);}}
            var prices=new HashMap<String,Double>();for(var definition:plugin.legacyStructures().listDefinitions(Integer.MAX_VALUE))prices.put(definition.id(),definition.upkeep());
            double structures=0;try(var s=db.prepareStatement("SELECT structure_id,COUNT(*) FROM structure_builds WHERE town_id=? AND status IN ('QUEUED','BUILDING','COMPLETE') GROUP BY structure_id")){s.setLong(1,town);try(var r=s.executeQuery()){while(r.next())structures+=prices.getOrDefault(r.getString(1),0.0)*r.getLong(2);}}
            long total=0;for(var charge:plan(db))if(charge.town()==town){total=charge.amount();break;}return new Breakdown(plugin.townUpgrades().level(level).upkeep(),structures,total);
        });
    }
    public UpkeepService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;
        extraTownCost=(long)YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/civ.yml")).getDouble("civ.town_upkeep",500);
        wonders=com.avrgaming.civcraft.modern.wonder.WonderEconomy.load(new File(plugin.getDataFolder(),"civcraft/data/buffs.yml"));
        warUpkeep=com.avrgaming.civcraft.modern.war.WarUpkeepRules.load(new File(plugin.getDataFolder(),"civcraft/data/war.yml"));scoreRules=new com.avrgaming.civcraft.modern.war.RevolutionRules(plugin.getDataFolder().toPath().resolve("civcraft/data").toFile());
        storage.transaction(db->{UpkeepStore.install(db);com.avrgaming.civcraft.modern.wonder.WonderIncomeStore.install(db);com.avrgaming.civcraft.modern.wonder.ColossusLeechStore.install(db);com.avrgaming.civcraft.modern.wonder.ColossusLeechStore.reconcile(db,true);return null;});
        storage.transaction(db->{com.avrgaming.civcraft.modern.war.WarUpkeepRules.install(db);return null;});
    }
    public void reconcileWonders(){try{storage.transaction(db->{com.avrgaming.civcraft.modern.wonder.ColossusLeechStore.reconcile(db,false);return null;});}catch(SQLException failure){plugin.getLogger().warning("Colossus target update failed: "+failure.getMessage());}}
    public void refreshScores(){try{storage.transaction(db->{com.avrgaming.civcraft.modern.war.LegacyScoreStore.refresh(db,scoreRules,System.currentTimeMillis());return null;});}catch(SQLException failure){plugin.getLogger().warning("Legacy score refresh failed: "+failure.getMessage());}}
    private List<UpkeepStore.Charge> plan(Connection db)throws SQLException {
        Map<String,Double> costs=new HashMap<>();
        for(var definition:plugin.legacyStructures().listDefinitions(Integer.MAX_VALUE))costs.put(definition.id(),definition.upkeep());
        List<UpkeepStore.Charge> charges=new ArrayList<>();
        record Town(long id,long civ,int level,String government,long capital,boolean administrative) {}
        List<Town> towns=new ArrayList<>();
        try(var rows=db.createStatement().executeQuery("""
                SELECT t.id,t.civ_id,t.level,c.government,
                COALESCE(c.capital_town_id,(SELECT MIN(first.id) FROM towns first WHERE first.civ_id=c.id)),
                EXISTS(SELECT 1 FROM administrative_civilizations a WHERE a.civ_id=c.id)
                FROM towns t JOIN civilizations c ON c.id=t.civ_id
                """+" WHERE "+com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.visible(db,"towns","t.id"))) {
            while(rows.next())towns.add(new Town(rows.getLong(1),rows.getLong(2),rows.getInt(3),rows.getString(4),rows.getLong(5),rows.getBoolean(6)));
        }
        for(var town:towns) {
            if(town.administrative()){charges.add(new UpkeepStore.Charge(town.id(),town.civ(),town.id()==town.capital(),0));continue;}
            double total=plugin.townUpgrades().level(town.level()).upkeep();
            try(var query=db.prepareStatement("SELECT structure_id,COUNT(*) FROM structure_builds WHERE town_id=? AND status IN ('QUEUED','BUILDING','COMPLETE') GROUP BY structure_id")) {
                query.setLong(1,town.id());try(var rows=query.executeQuery()){while(rows.next())total+=costs.getOrDefault(rows.getString(1),0.0)*rows.getInt(2);}
            }
            total*=plugin.governments().upkeepRate(com.avrgaming.civcraft.modern.war.OccupationRules.government(db,town.id()));
            total=wonders.upkeep(db,town.id(),total);
            if(!Double.isFinite(total) || total<0 || total>1_000_000_000_000L)throw new SQLException("Unsupported town upkeep");
            charges.add(new UpkeepStore.Charge(town.id(),town.civ(),town.id()==town.capital(),(long)Math.floor(total)));
        }
        return charges;
    }
    public DailyResult daily(Connection db,String day,long now)throws SQLException {
        var towns=plan(db);Map<Long,Long> civs=new LinkedHashMap<>();
        for(var town:towns)civs.merge(town.civ(),town.capital()||administrative(db,town.civ())?0:extraTownCost,Math::addExact);
        var warCosts=warUpkeep.plan(db);var totals=new HashMap<Long,Double>();for(var cost:warCosts)totals.merge(cost.civ(),cost.coins(),Double::sum);for(var total:totals.entrySet()){if(!Double.isFinite(total.getValue())||total.getValue()<0||total.getValue()>1e12)throw new SQLException("War upkeep overflow");civs.merge(total.getKey(),(long)Math.floor(total.getValue()),Math::addExact);}
        var bonuses=new ArrayList<com.avrgaming.civcraft.modern.wonder.WonderIncomeStore.Credit>();
        com.avrgaming.civcraft.modern.wonder.ColossusLeechStore.reconcile(db,false);
        var payments=UpkeepStore.daily(db,day,now,towns,civs,charge->com.avrgaming.civcraft.modern.wonder.ColossusLeechStore.pay(db,day,charge,wonders.leechRate()),()->{bonuses.addAll(com.avrgaming.civcraft.modern.wonder.WonderIncomeStore.pay(db,day,now,wonders,storage.capturedPenalty()));com.avrgaming.civcraft.modern.war.WarUpkeepRules.audit(db,day,warCosts);});
        return new DailyResult(payments,List.copyOf(bonuses));
    }
    private static boolean administrative(Connection db,long civ)throws SQLException {try(var s=db.prepareStatement("SELECT 1 FROM administrative_civilizations WHERE civ_id=?")){s.setLong(1,civ);try(var r=s.executeQuery()){return r.next();}}}
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
            if(civilization&&!administrative(db,id))for(var charge:plan(db))if(charge.civ()==id && !charge.capital())cost+=extraTownCost;
            if(civilization){double war=0;for(var charge:warUpkeep.plan(db))if(charge.civ()==id)war+=charge.coins();if(!Double.isFinite(war)||war>1e12)throw new SQLException("War upkeep overflow");cost=Math.addExact(cost,(long)Math.floor(war));}
            return "Содержание: "+cost+" монет/день. Казна: "+balance+". Долг: "+debt+". Дней в долгу: "+days+".";
        });
        player.sendMessage(Component.text(info));
    }
}
