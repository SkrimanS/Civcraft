package com.avrgaming.civcraft.modern.research;

import java.math.*;
import java.sql.*;
import com.avrgaming.civcraft.modern.town.UpkeepStore;

/** Tax conversion shares the income receipt transaction; treasury fractions survive restarts. */
public final class CivilizationScienceStore {
    private CivilizationScienceStore() {}
    public static void install(Connection db,double coinsPerBeaker)throws SQLException {
        if(!Double.isFinite(coinsPerBeaker) || coinsPerBeaker<=0)throw new IllegalArgumentException("Неверная стоимость научного очка в монетах");
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS civilization_science(civ_id INTEGER PRIMARY KEY REFERENCES civilizations(id) ON DELETE CASCADE,percentage REAL NOT NULL DEFAULT 0 CHECK(percentage BETWEEN 0 AND 1),treasury_fraction TEXT NOT NULL DEFAULT '0',extra_beakers REAL NOT NULL DEFAULT 0)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS science_conversion(singleton INTEGER PRIMARY KEY CHECK(singleton=1),coins_per_beaker REAL NOT NULL CHECK(coins_per_beaker>0))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS victory_countdowns(civ_id INTEGER NOT NULL REFERENCES civilizations(id) ON DELETE CASCADE,condition_id TEXT NOT NULL,days_held INTEGER NOT NULL,last_day TEXT NOT NULL,PRIMARY KEY(civ_id,condition_id))");
        }
        try(var s=db.prepareStatement("INSERT INTO science_conversion VALUES(1,?) ON CONFLICT(singleton) DO UPDATE SET coins_per_beaker=excluded.coins_per_beaker")){s.setDouble(1,coinsPerBeaker);s.executeUpdate();}
    }
    public static double percentage(Connection db,long civ)throws SQLException {
        try(var s=db.prepareStatement("SELECT percentage FROM civilization_science WHERE civ_id=?")){s.setLong(1,civ);try(var r=s.executeQuery()){return r.next()?r.getDouble(1):0;}}
    }
    public static void setPercentage(Connection db,long civ,double value)throws SQLException {
        if(!Double.isFinite(value) || value<0 || value>1)throw new IllegalArgumentException("Научный бюджет: от 0 до 100%.");
        try(var s=db.prepareStatement("INSERT INTO civilization_science(civ_id,percentage) VALUES(?,?) ON CONFLICT(civ_id) DO UPDATE SET percentage=excluded.percentage")){s.setLong(1,civ);s.setDouble(2,value);s.executeUpdate();}
    }
    public static void cap(Connection db,long civ,double maximum)throws SQLException {
        try(var s=db.prepareStatement("UPDATE civilization_science SET percentage=MIN(percentage,?) WHERE civ_id=?")){s.setDouble(1,maximum);s.setLong(2,civ);s.executeUpdate();}
    }
    public static boolean victoryActive(Connection db,long civ)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM victory_countdowns WHERE civ_id=? AND condition_id='end_science'")){s.setLong(1,civ);try(var r=s.executeQuery()){return r.next();}}
    }
    public static void extra(Connection db,long civ,double amount)throws SQLException {
        if(!Double.isFinite(amount) || amount<0)throw new SQLException("Invalid extra science");
        try(var s=db.prepareStatement("INSERT INTO civilization_science(civ_id,extra_beakers) VALUES(?,?) ON CONFLICT(civ_id) DO UPDATE SET extra_beakers=extra_beakers+excluded.extra_beakers WHERE extra_beakers<=?")){s.setLong(1,civ);s.setDouble(2,amount);s.setDouble(3,Double.MAX_VALUE-amount);if(s.executeUpdate()!=1)throw new SQLException("Extra science overflow");}
    }
    public static void creditTax(Connection db,long civ,long amount)throws SQLException {creditTax(db,civ,BigDecimal.valueOf(amount));}
    public static void creditTax(Connection db,long civ,BigDecimal gross)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Science tax credit requires a transaction");
        if(gross.signum()<0)throw new SQLException("Negative science tax payment");
        if(gross.signum()==0)return;
        BigDecimal converted=gross.multiply(BigDecimal.valueOf(percentage(db,civ))),money=gross.subtract(converted);
        try(var s=db.prepareStatement("SELECT treasury_fraction FROM civilization_science WHERE civ_id=?")){s.setLong(1,civ);try(var r=s.executeQuery()){if(r.next())money=money.add(new BigDecimal(r.getString(1)));}}
        long whole;try{whole=money.toBigInteger().longValueExact();}catch(ArithmeticException invalid){throw new SQLException("Science treasury overflow",invalid);}
        if(whole>0)UpkeepStore.deposit(db,"civilizations",civ,whole);
        try(var s=db.prepareStatement("INSERT INTO civilization_science(civ_id,treasury_fraction) VALUES(?,?) ON CONFLICT(civ_id) DO UPDATE SET treasury_fraction=excluded.treasury_fraction")){s.setLong(1,civ);s.setString(2,money.subtract(BigDecimal.valueOf(whole)).toPlainString());s.executeUpdate();}
        if(converted.signum()==0)return;
        double price;try(var s=db.createStatement();var r=s.executeQuery("SELECT coins_per_beaker FROM science_conversion WHERE singleton=1")){if(!r.next())throw new SQLException("Missing science conversion configuration");price=r.getDouble(1);}
        int libraries;try(var s=db.prepareStatement("SELECT COUNT(DISTINCT t.id) FROM towns t JOIN active_structure_builds b ON b.town_id=t.id LEFT JOIN building_combat_health h ON h.build_id=b.id WHERE t.civ_id=? AND b.structure_id='w_greatlibrary' AND COALESCE(h.hitpoints,b.max_hitpoints)>0")){s.setLong(1,civ);try(var r=s.executeQuery()){r.next();libraries=r.getInt(1);}}
        BigDecimal beakers=converted.multiply(BigDecimal.valueOf(2).pow(libraries)).divide(BigDecimal.valueOf(price),1,RoundingMode.HALF_EVEN);
        double earned=beakers.doubleValue();if(!Double.isFinite(earned))throw new SQLException("Tax science overflow");if(earned<=0)return;
        try(var s=db.prepareStatement("UPDATE civ_research SET progress=progress+?,updated_at=? WHERE civ_id=? AND progress<=?")){s.setDouble(1,earned);s.setLong(2,System.currentTimeMillis());s.setLong(3,civ);s.setDouble(4,Double.MAX_VALUE-earned);if(s.executeUpdate()>0)return;}
        // As in legacy, unused tax beakers are spent, unless a scientific countdown is active.
        if(victoryActive(db,civ))extra(db,civ,earned);
    }
}
