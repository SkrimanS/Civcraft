package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import java.util.*;

public final class UpgradeStore {
    private UpgradeStore() {}
    public static void install(Connection db)throws SQLException {
        com.avrgaming.civcraft.modern.trade.GrocerStore.install(db);
        com.avrgaming.civcraft.modern.trade.LibraryStore.install(db);
        ProductionStore.install(db);
        com.avrgaming.civcraft.modern.trade.TradeShipStore.install(db);
        try(var statement=db.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS town_upgrades(
                    town_id INTEGER NOT NULL REFERENCES towns(id) ON DELETE CASCADE,
                    upgrade_id TEXT NOT NULL,purchased_at INTEGER NOT NULL,PRIMARY KEY(town_id,upgrade_id))
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bank_interest_accounts(
                    town_id INTEGER PRIMARY KEY REFERENCES towns(id) ON DELETE CASCADE,
                    interest_rate REAL NOT NULL DEFAULT 0,principal INTEGER NOT NULL DEFAULT 0)
                    """);
            statement.executeUpdate("""
                    CREATE TRIGGER IF NOT EXISTS bank_principal_on_withdrawal
                    AFTER UPDATE OF coins ON towns WHEN NEW.coins < OLD.coins
                    BEGIN UPDATE bank_interest_accounts SET principal=MIN(principal,NEW.coins) WHERE town_id=NEW.id; END
                    """);
        }
    }
    public static Set<String> purchased(Connection db,long town)throws SQLException {
        Set<String> result=new LinkedHashSet<>();
        try(var query=db.prepareStatement("SELECT upgrade_id FROM town_upgrades WHERE town_id=?")) {
            query.setLong(1,town);try(var rows=query.executeQuery()){while(rows.next())result.add(rows.getString(1));}
        }
        return result;
    }
    public static void buy(Connection db,long town,UUID actor,UpgradeDefinition upgrade,long now)throws SQLException {
        if(!upgrade.supported())throw new IllegalArgumentException("Это улучшение пока недоступно.");
        long civ;
        try(var query=db.prepareStatement("SELECT t.civ_id,t.mayor_uuid,c.leader_uuid FROM towns t JOIN civilizations c ON c.id=t.civ_id WHERE t.id=?")) {
            query.setLong(1,town);try(var row=query.executeQuery()) {
                if(!row.next())throw new IllegalArgumentException("Город не найден.");
                civ=row.getLong(1);
                if(!actor.toString().equals(row.getString(2)) && !actor.toString().equals(row.getString(3)))throw new IllegalArgumentException("Улучшения покупает мэр или лидер цивилизации.");
            }
        }
        var owned=purchased(db,town);
        if(owned.contains(upgrade.id()))throw new IllegalArgumentException("Улучшение уже куплено.");
        for(String required:ids(upgrade.previous()))if(!owned.contains(required))throw new IllegalArgumentException("Сначала купите улучшение: "+required);
        for(String required:ids(upgrade.technology())) {
            try(var query=db.prepareStatement("SELECT 1 FROM civ_technologies WHERE civ_id=? AND tech_id=?")) {
                query.setLong(1,civ);query.setString(2,required);try(var rows=query.executeQuery()){if(!rows.next())throw new IllegalArgumentException("Требуется технология: "+required);}
            }
        }
        for(String required:ids(upgrade.structure())) {
            try(var query=db.prepareStatement("SELECT 1 FROM structure_builds WHERE town_id=? AND structure_id=? AND status='COMPLETE' LIMIT 1")) {
                query.setLong(1,town);query.setString(2,required);try(var rows=query.executeQuery()){if(!rows.next())throw new IllegalArgumentException("Требуется завершённая постройка: "+required);}
            }
        }
        if(upgrade.action().equals("set_fish_hatchery_level") || upgrade.action().equals("set_trommel_level"))ProductionStore.requireTownUnlocked(db,town);
        try(var debit=db.prepareStatement("UPDATE towns SET coins=coins-? WHERE id=? AND coins>=?")) {
            debit.setLong(1,upgrade.cost());debit.setLong(2,town);debit.setLong(3,upgrade.cost());
            if(debit.executeUpdate()!=1)throw new IllegalArgumentException("В казне города недостаточно монет: требуется "+upgrade.cost()+".");
        }
        switch(upgrade.action()) {
            case "set_town_level" -> {
                int level=Integer.parseInt(upgrade.value());if(level<1 || level>9)throw new IllegalArgumentException("Invalid town level");
                try(var update=db.prepareStatement("UPDATE towns SET level=MAX(level,?) WHERE id=?")){update.setInt(1,level);update.setLong(2,town);update.executeUpdate();}
            }
            case "set_bank_level" -> {
                int level=Integer.parseInt(upgrade.value());if(level<1 || level>10)throw new IllegalArgumentException("Invalid bank level");
                try(var update=db.prepareStatement("INSERT INTO town_trade_settings(town_id,bank_level) VALUES(?,?) ON CONFLICT(town_id) DO UPDATE SET bank_level=MAX(bank_level,excluded.bank_level)")){update.setLong(1,town);update.setInt(2,level);update.executeUpdate();}
            }
            case "set_bank_interest" -> {
                double rate=Double.parseDouble(upgrade.value());if(!Double.isFinite(rate) || rate<0 || rate>1)throw new IllegalArgumentException("Invalid interest rate");
                try(var update=db.prepareStatement("INSERT INTO bank_interest_accounts(town_id,interest_rate,principal) SELECT id,?,coins FROM towns WHERE id=? ON CONFLICT(town_id) DO UPDATE SET interest_rate=MAX(interest_rate,excluded.interest_rate)")){update.setDouble(1,rate);update.setLong(2,town);update.executeUpdate();}
            }
            case "set_grocer_level" -> com.avrgaming.civcraft.modern.trade.GrocerStore.level(db,town,Integer.parseInt(upgrade.value()));
            case "set_library_level" -> com.avrgaming.civcraft.modern.trade.LibraryStore.level(db,town,Integer.parseInt(upgrade.value()));
            case "set_fish_hatchery_level" -> ProductionStore.fisheryLevel(db,town,Integer.parseInt(upgrade.value()));
            case "set_trommel_level" -> ProductionStore.trommelLevel(db,town,Integer.parseInt(upgrade.value()));
            case "enable_library_enchantment" -> com.avrgaming.civcraft.modern.trade.LibraryStore.add(db,town,com.avrgaming.civcraft.modern.trade.LibraryOffer.parse(upgrade.value()));
            case "set_tradeship_upgrade_level" -> com.avrgaming.civcraft.modern.trade.TradeShipStore.upgrade(db,town,Integer.parseInt(upgrade.value()));
            default -> throw new IllegalArgumentException("Unknown upgrade action");
        }
        try(var insert=db.prepareStatement("INSERT INTO town_upgrades VALUES(?,?,?)")){insert.setLong(1,town);insert.setString(2,upgrade.id());insert.setLong(3,now);insert.executeUpdate();}
    }
    private static List<String> ids(String value){return Arrays.stream(value.split("[,;\\s]+" )).filter(s->!s.isBlank()).toList();}
}
