package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import java.util.*;

/** The day, withdrawals, debt and audit rows share one caller-owned transaction. */
public final class UpkeepStore {
    private UpkeepStore() {}
    public record Charge(long town,long civ,boolean capital,long amount) {}
    public record Payment(String kind,long id,long cost,long paid,long debt) {}
    @FunctionalInterface public interface BeforeCivilizationCharges {void pay()throws SQLException;}
    public static void install(Connection db)throws SQLException {
        column(db,"towns","debt","INTEGER NOT NULL DEFAULT 0");
        column(db,"towns","days_in_debt","INTEGER NOT NULL DEFAULT 0");
        column(db,"civilizations","debt","INTEGER NOT NULL DEFAULT 0");
        column(db,"civilizations","days_in_debt","INTEGER NOT NULL DEFAULT 0");
        column(db,"civilizations","capital_town_id","INTEGER");
        try(var statement=db.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS upkeep_payments(
                    day TEXT NOT NULL,kind TEXT NOT NULL,target_id INTEGER NOT NULL,
                    cost INTEGER NOT NULL,paid INTEGER NOT NULL,debt_added INTEGER NOT NULL,
                    PRIMARY KEY(day,kind,target_id))
                    """);
        }
    }
    private static void column(Connection db,String table,String name,String type)throws SQLException {
        try(var statement=db.createStatement()) {
            boolean found=false;
            try(var columns=statement.executeQuery("PRAGMA table_info("+table+")")){while(columns.next())if(name.equals(columns.getString("name")))found=true;}
            if(!found)statement.executeUpdate("ALTER TABLE "+table+" ADD COLUMN "+name+" "+type);
        }
    }
    public static List<Payment> daily(Connection db,String day,long now,List<Charge> towns,Map<Long,Long> civCharges)throws SQLException {
        return daily(db,day,now,towns,civCharges,()->{});
    }
    public static List<Payment> daily(Connection db,String day,long now,List<Charge> towns,Map<Long,Long> civCharges,BeforeCivilizationCharges bonuses)throws SQLException {
        try(var insert=db.prepareStatement("INSERT OR IGNORE INTO daily_economy_events VALUES('upkeep',?,?)")) {
            insert.setString(1,day);insert.setLong(2,now);if(insert.executeUpdate()==0)return List.of();
        }
        List<Payment> result=new ArrayList<>();
        for(var charge:towns) {
            if(charge.amount()<0)throw new SQLException("Negative upkeep");
            long paid=withdrawAvailable(db,"towns",charge.town(),charge.amount());
            long shortage=charge.amount()-paid;
            if(shortage>0 && charge.capital()) {
                long covered=withdrawAvailable(db,"civilizations",charge.civ(),shortage);
                paid+=covered;shortage-=covered;
                addDebt(db,"civilizations",charge.civ(),shortage);
            } else addDebt(db,"towns",charge.town(),shortage);
            Payment payment=new Payment("town",charge.town(),charge.amount(),paid,shortage);audit(db,day,payment);result.add(payment);
        }
        bonuses.pay();
        for(var charge:civCharges.entrySet()) {
            long paid=withdrawAvailable(db,"civilizations",charge.getKey(),charge.getValue());
            long shortage=charge.getValue()-paid;addDebt(db,"civilizations",charge.getKey(),shortage);
            Payment payment=new Payment("civ",charge.getKey(),charge.getValue(),paid,shortage);audit(db,day,payment);result.add(payment);
        }
        for(String table:List.of("towns","civilizations"))try(var statement=db.createStatement()) {
            statement.executeUpdate("UPDATE "+table+" SET days_in_debt=CASE WHEN debt>0 THEN days_in_debt+1 ELSE 0 END");
        }
        return result;
    }
    private static long withdrawAvailable(Connection db,String table,long id,long amount)throws SQLException {
        if(amount<0)throw new SQLException("Negative upkeep");
        long balance;
        try(var query=db.prepareStatement("SELECT coins FROM "+table+" WHERE id=?")) {
            query.setLong(1,id);try(var row=query.executeQuery()){if(!row.next())throw new SQLException("Missing upkeep recipient");balance=row.getLong(1);}
        }
        long paid=Math.min(Math.max(0,balance),amount);
        try(var update=db.prepareStatement("UPDATE "+table+" SET coins=coins-? WHERE id=?")){update.setLong(1,paid);update.setLong(2,id);update.executeUpdate();}
        return paid;
    }
    private static void addDebt(Connection db,String table,long id,long amount)throws SQLException {
        if(amount==0)return;
        try(var update=db.prepareStatement("UPDATE "+table+" SET debt=debt+? WHERE id=? AND debt<=?")) {
            update.setLong(1,amount);update.setLong(2,id);update.setLong(3,Long.MAX_VALUE-amount);
            if(update.executeUpdate()!=1)throw new SQLException("Unsupported upkeep debt");
        }
    }
    private static void audit(Connection db,String day,Payment payment)throws SQLException {
        try(var insert=db.prepareStatement("INSERT INTO upkeep_payments VALUES(?,?,?,?,?,?)")) {
            insert.setString(1,day);insert.setString(2,payment.kind());insert.setLong(3,payment.id());insert.setLong(4,payment.cost());insert.setLong(5,payment.paid());insert.setLong(6,payment.debt());insert.executeUpdate();
        }
    }
    /** Player deposits repay outstanding debt first, as in the original treasury. */
    public static void deposit(Connection db,String table,long id,long amount)throws SQLException {
        if(!List.of("towns","civilizations").contains(table) || amount<=0)throw new IllegalArgumentException("Укажите положительную сумму.");
        try(var update=db.prepareStatement("UPDATE "+table+" SET coins=coins+MAX(0,?-debt),debt=MAX(0,debt-?),days_in_debt=CASE WHEN debt<=? THEN 0 ELSE days_in_debt END WHERE id=? AND coins<=?-MAX(0,?-debt)")) {
            update.setLong(1,amount);update.setLong(2,amount);update.setLong(3,amount);update.setLong(4,id);
            update.setLong(5,Long.MAX_VALUE);update.setLong(6,amount);
            if(update.executeUpdate()!=1)throw new SQLException("Missing treasury or treasury overflow");
        }
    }
    public static void transferDeposit(Connection db,String table,long id,UUID player,long amount)throws SQLException {
        if(!List.of("towns","civilizations").contains(table) || amount<=0)throw new IllegalArgumentException("Укажите положительную сумму.");
        String membership=table.equals("towns")?"town_id":"civ_id";
        try(var update=db.prepareStatement("UPDATE residents SET coins=coins-? WHERE uuid=? AND "+membership+"=? AND coins>=?")) {
            update.setLong(1,amount);update.setString(2,player.toString());update.setLong(3,id);update.setLong(4,amount);
            if(update.executeUpdate()!=1)throw new IllegalArgumentException("Недостаточно монет или изменилось членство.");
        }
        deposit(db,table,id,amount);
    }
    public static long payDebt(Connection db,String table,long id,long limit)throws SQLException {
        if(!List.of("towns","civilizations").contains(table) || limit<=0)throw new IllegalArgumentException("Укажите положительную сумму.");
        long paid;
        try(var query=db.prepareStatement("SELECT coins,debt FROM "+table+" WHERE id=?")) {
            query.setLong(1,id);try(var row=query.executeQuery()) {
                if(!row.next())throw new SQLException("Missing debt account");
                paid=Math.min(limit,Math.min(row.getLong(1),row.getLong(2)));
            }
        }
        if(paid<=0)throw new IllegalArgumentException("Нет долга для погашения или в казне недостаточно монет.");
        try(var update=db.prepareStatement("UPDATE "+table+" SET coins=coins-?,debt=debt-?,days_in_debt=CASE WHEN debt-?=0 THEN 0 ELSE days_in_debt END WHERE id=?")) {
            update.setLong(1,paid);update.setLong(2,paid);update.setLong(3,paid);update.setLong(4,id);update.executeUpdate();
        }
        return paid;
    }
}
