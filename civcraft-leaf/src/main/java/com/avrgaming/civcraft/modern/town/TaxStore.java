package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import java.util.*;

/** Taxes, their recipients and eviction counters run inside the daily transaction. */
public final class TaxStore {
    private TaxStore() {}
    public record Notice(UUID player,long town,long debt,int days,boolean evicted) {}
    public static void install(Connection db)throws SQLException {
        column(db,"towns","flat_tax","INTEGER NOT NULL DEFAULT 0");
        column(db,"towns","property_tax_rate","REAL NOT NULL DEFAULT 0");
        column(db,"civilizations","income_tax_rate","REAL NOT NULL DEFAULT 0");
        column(db,"residents","debt","INTEGER NOT NULL DEFAULT 0");
        column(db,"residents","days_til_evict","INTEGER NOT NULL DEFAULT 0");
        column(db,"town_claims","owner_uuid","TEXT");
        column(db,"town_claims","purchase_value","INTEGER NOT NULL DEFAULT 0");
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS town_assistants(town_id INTEGER NOT NULL,player_uuid TEXT NOT NULL,PRIMARY KEY(town_id,player_uuid))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS resident_tax_payments(day TEXT NOT NULL,player_uuid TEXT NOT NULL,town_id INTEGER NOT NULL,assessed INTEGER NOT NULL,paid INTEGER NOT NULL,debt_added INTEGER NOT NULL,PRIMARY KEY(day,player_uuid))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS resident_rejoin_cooldowns(player_uuid TEXT PRIMARY KEY,civ_id INTEGER NOT NULL,departed_at INTEGER NOT NULL)");
            s.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS resident_tax_membership_cleanup AFTER UPDATE OF town_id ON residents
                WHEN OLD.town_id IS NOT NULL AND OLD.town_id IS NOT NEW.town_id
                BEGIN
                  UPDATE residents SET debt=0,days_til_evict=0 WHERE uuid=NEW.uuid;
                  UPDATE town_claims SET owner_uuid=NULL WHERE town_id=OLD.town_id AND owner_uuid=NEW.uuid;
                  DELETE FROM town_assistants WHERE town_id=OLD.town_id AND player_uuid=NEW.uuid;
                END
                """);
            s.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS resident_tax_rejoin_log AFTER UPDATE OF town_id ON residents
                WHEN OLD.town_id IS NOT NULL AND OLD.town_id IS NOT NEW.town_id
                BEGIN
                  INSERT OR REPLACE INTO resident_rejoin_cooldowns SELECT NEW.uuid,civ_id,NEW.updated_at FROM towns WHERE id=OLD.town_id;
                END
                """);
        }
    }
    private static void column(Connection db,String table,String name,String type)throws SQLException {
        try(var s=db.createStatement()) {
            boolean found=false;try(var r=s.executeQuery("PRAGMA table_info("+table+")")){while(r.next())if(name.equals(r.getString("name")))found=true;}
            if(!found)s.executeUpdate("ALTER TABLE "+table+" ADD COLUMN "+name+" "+type);
        }
    }
    public static boolean collect(Connection db,String day,long now)throws SQLException {
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO daily_economy_events VALUES('resident_taxes',?,?)")) {
            s.setString(1,day);s.setLong(2,now);if(s.executeUpdate()==0)return false;
        }
        record Bill(String player,long town,long civ,long balance,long debt,long flat,double property,double income) {}
        record TownTax(long civ,double rate,long paid) {}
        Map<Long,TownTax> totals=new LinkedHashMap<>();
        var bills=new ArrayList<Bill>();
        try(var s=db.createStatement();var r=s.executeQuery("""
            SELECT r.uuid,t.id,t.civ_id,r.coins,r.debt,t.flat_tax,t.property_tax_rate,c.income_tax_rate
            FROM residents r JOIN towns t ON t.id=r.town_id JOIN civilizations c ON c.id=t.civ_id
            WHERE r.uuid<>t.mayor_uuid AND NOT EXISTS(SELECT 1 FROM town_assistants a WHERE a.town_id=t.id AND a.player_uuid=r.uuid)
            """)) {
            while(r.next())bills.add(new Bill(r.getString(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5),r.getLong(6),r.getDouble(7),r.getDouble(8)));
        }
        for(var bill:bills) {
            double propertyValue=0;
            try(var s=db.prepareStatement("SELECT purchase_value FROM town_claims WHERE town_id=? AND owner_uuid=?")) {
                s.setLong(1,bill.town());s.setString(2,bill.player());try(var r=s.executeQuery()){while(r.next())propertyValue+=r.getLong(1)*bill.property();}
            }
            long property=whole(propertyValue);
            if(bill.flat()>Long.MAX_VALUE-property)throw new SQLException("Tax assessment overflow");
            long assessed=bill.flat()+property;
            if(assessed<0 || bill.balance()<0 || bill.debt()<0)throw new SQLException("Invalid resident tax account");
            long paid=Math.min(bill.balance(),assessed),added=assessed-paid;
            try(var s=db.prepareStatement("UPDATE residents SET coins=coins-?,debt=debt+?,days_til_evict=CASE WHEN debt=0 AND ?>0 THEN 3 ELSE days_til_evict END WHERE uuid=? AND debt<=?")) {
                s.setLong(1,paid);s.setLong(2,added);s.setLong(3,added);s.setString(4,bill.player());s.setLong(5,Long.MAX_VALUE-added);
                if(s.executeUpdate()!=1)throw new SQLException("Resident tax debt overflow");
            }
            long previous=totals.containsKey(bill.town())?totals.get(bill.town()).paid():0;
            if(previous>Long.MAX_VALUE-paid)throw new SQLException("Town tax total overflow");
            totals.put(bill.town(),new TownTax(bill.civ(),bill.income(),previous+paid));
            try(var s=db.prepareStatement("INSERT INTO resident_tax_payments VALUES(?,?,?,?,?,?)")) {
                s.setString(1,day);s.setString(2,bill.player());s.setLong(3,bill.town());s.setLong(4,assessed);s.setLong(5,paid);s.setLong(6,added);s.executeUpdate();
            }
        }
        for(var entry:totals.entrySet())creditTax(db,entry.getKey(),entry.getValue().civ(),entry.getValue().paid(),entry.getValue().rate());
        return true;
    }
    static long whole(double amount)throws SQLException {
        if(!Double.isFinite(amount) || amount<0 || amount>=0x1p63)throw new SQLException("Unsupported tax amount");
        return (long)Math.floor(amount);
    }
    private static void creditTax(Connection db,long town,long civ,long paid,double rate)throws SQLException {
        if(!Double.isFinite(rate) || rate<0 || rate>1)throw new SQLException("Unsupported civilization tax rate");
        long income=whole(paid*rate);
        credit(db,"towns",town,paid-income);credit(db,"civilizations",civ,income);
    }
    private static void credit(Connection db,String table,long id,long amount)throws SQLException {
        try(var s=db.prepareStatement("UPDATE "+table+" SET coins=coins+? WHERE id=? AND coins<=?")) {
            s.setLong(1,amount);s.setLong(2,id);s.setLong(3,Long.MAX_VALUE-amount);if(s.executeUpdate()!=1)throw new SQLException("Tax treasury missing or overflow");
        }
    }
    public static List<Notice> advanceGrace(Connection db,String day,long now)throws SQLException {
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO daily_economy_events VALUES('resident_grace',?,?)")) {
            s.setString(1,day);s.setLong(2,now);if(s.executeUpdate()==0)return List.of();
        }
        var notices=new ArrayList<Notice>();
        try(var s=db.createStatement();var r=s.executeQuery("SELECT uuid,town_id,debt,days_til_evict FROM residents WHERE town_id IS NOT NULL AND days_til_evict>0")) {
            while(r.next())notices.add(new Notice(UUID.fromString(r.getString(1)),r.getLong(2),r.getLong(3),r.getInt(4)-1,r.getInt(4)==1));
        }
        for(var notice:notices) {
            if(notice.evicted())removeTownMembership(db,notice.player(),notice.town(),now);
            else try(var s=db.prepareStatement("UPDATE residents SET days_til_evict=? WHERE uuid=?")){s.setInt(1,notice.days());s.setString(2,notice.player().toString());s.executeUpdate();}
        }
        return notices;
    }
    public static void removeTownMembership(Connection db,UUID player,long town,long now)throws SQLException {
        try(var s=db.prepareStatement("UPDATE residents SET town_id=NULL,debt=0,days_til_evict=0,updated_at=? WHERE uuid=? AND town_id=?")) {
            s.setLong(1,now);s.setString(2,player.toString());s.setLong(3,town);s.executeUpdate();
        }
        try(var s=db.prepareStatement("UPDATE town_claims SET owner_uuid=NULL WHERE town_id=? AND owner_uuid=?")){s.setLong(1,town);s.setString(2,player.toString());s.executeUpdate();}
        try(var s=db.prepareStatement("DELETE FROM town_assistants WHERE town_id=? AND player_uuid=?")){s.setLong(1,town);s.setString(2,player.toString());s.executeUpdate();}
        try(var s=db.prepareStatement("DELETE FROM settlement_invites WHERE target_uuid=?")){s.setString(1,player.toString());s.executeUpdate();}
    }
    public static long payDebt(Connection db,UUID player)throws SQLException {
        long debt,town;
        try(var s=db.prepareStatement("SELECT r.debt,r.coins,t.id,t.civ_id,c.income_tax_rate FROM residents r JOIN towns t ON t.id=r.town_id JOIN civilizations c ON c.id=t.civ_id WHERE r.uuid=?")) {
            s.setString(1,player.toString());try(var r=s.executeQuery()) {
                if(!r.next())throw new IllegalArgumentException("Вы не состоите в городе.");
                debt=r.getLong(1);if(debt<=0)throw new IllegalArgumentException("У вас нет налогового долга.");
                if(r.getLong(2)<debt)throw new IllegalArgumentException("Для погашения долга нужно "+debt+" монет.");
                town=r.getLong(3);
            }
        }
        try(var s=db.prepareStatement("UPDATE residents SET coins=coins-?,debt=0,days_til_evict=0 WHERE uuid=?")){s.setLong(1,debt);s.setString(2,player.toString());s.executeUpdate();}
        // Original payOffDebt pays the town directly, without another income tax.
        credit(db,"towns",town,debt);return debt;
    }
    public static void validateRejoin(Connection db,UUID player,long now,long cooldown)throws SQLException {
        try(var s=db.prepareStatement("SELECT rc.departed_at FROM resident_rejoin_cooldowns rc JOIN civilizations c ON c.id=rc.civ_id WHERE rc.player_uuid=?")) {
            s.setString(1,player.toString());try(var r=s.executeQuery()) {
                if(r.next() && now-r.getLong(1)<cooldown)throw new IllegalArgumentException("После выхода из города повторное вступление доступно через "+((cooldown+3599999)/3600000)+" ч.");
            }
        }
    }
}
