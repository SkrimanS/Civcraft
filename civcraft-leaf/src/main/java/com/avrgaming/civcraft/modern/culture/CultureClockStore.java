package com.avrgaming.civcraft.modern.culture;
import java.sql.*;
import java.util.*;

public final class CultureClockStore {
    private CultureClockStore() {}
    public record Production(long town,double culture,double unusedBeakers) {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS culture_science(town_id INTEGER PRIMARY KEY,unused_beakers REAL NOT NULL DEFAULT 0,FOREIGN KEY(town_id) REFERENCES towns(id) ON DELETE CASCADE)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS culture_clock(period INTEGER PRIMARY KEY,last_hour INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS culture_hours(period INTEGER NOT NULL,hour INTEGER NOT NULL,town_id INTEGER NOT NULL,from_buildings INTEGER NOT NULL,from_science INTEGER NOT NULL,PRIMARY KEY(period,hour,town_id))");
        }
    }
    /** Startup schedules the next boundary; missed offline hours grant no production. */
    public static void start(Connection db,long period,long hour)throws SQLException {
        try(var s=db.prepareStatement("INSERT INTO culture_clock VALUES(?,?) ON CONFLICT(period) DO UPDATE SET last_hour=excluded.last_hour")){s.setLong(1,period);s.setLong(2,hour);s.executeUpdate();}
    }
    public static void science(Connection db,long town,double earned)throws SQLException {
        if(!Double.isFinite(earned) || earned<0)throw new SQLException("Invalid unused science");
        try(var s=db.prepareStatement("INSERT INTO culture_science VALUES(?,?) ON CONFLICT(town_id) DO UPDATE SET unused_beakers=unused_beakers+excluded.unused_beakers")){s.setLong(1,town);s.setDouble(2,earned);s.executeUpdate();}
    }
    public static boolean due(Connection db,long period,long hour)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM culture_clock WHERE period=? AND last_hour<?")){s.setLong(1,period);s.setLong(2,hour);try(var r=s.executeQuery()){return r.next();}}
    }
    public static Map<Long,Long> hourly(Connection db,long period,long hour,List<Production> towns,double conversion)throws SQLException {
        if(!Double.isFinite(conversion) || conversion<0)throw new SQLException("Invalid culture conversion");
        try(var s=db.prepareStatement("UPDATE culture_clock SET last_hour=? WHERE period=? AND last_hour<?")){s.setLong(1,hour);s.setLong(2,period);s.setLong(3,hour);if(s.executeUpdate()!=1)return Map.of();}
        Map<Long,Long> changes=new LinkedHashMap<>();
        for(var town:towns) {
            if(!Double.isFinite(town.culture()) || town.culture()<0 || !Double.isFinite(town.unusedBeakers()) || town.unusedBeakers()<0)throw new SQLException("Invalid hourly culture");
            double scientific=town.unusedBeakers()*conversion;
            if(!Double.isFinite(scientific) || scientific>=0x1p63 || town.culture()>=0x1p63)throw new SQLException("Culture overflow");
            long building=Math.round(town.culture()),science=Math.round(scientific);
            if(building>Long.MAX_VALUE-science)throw new SQLException("Culture overflow");long amount=building+science;
            try(var s=db.prepareStatement("INSERT OR IGNORE INTO culture_hours VALUES(?,?,?,?,?)")) {
                s.setLong(1,period);s.setLong(2,hour);s.setLong(3,town.town());s.setLong(4,building);s.setLong(5,science);if(s.executeUpdate()==0)continue;
            }
            try(var s=db.prepareStatement("UPDATE towns SET accumulated_culture=accumulated_culture+? WHERE id=? AND accumulated_culture<=?")){s.setLong(1,amount);s.setLong(2,town.town());s.setLong(3,Long.MAX_VALUE-amount);if(s.executeUpdate()!=1)throw new SQLException("Missing culture town or overflow");}
            if(science>0)try(var s=db.prepareStatement("UPDATE culture_science SET unused_beakers=0 WHERE town_id=?")){s.setLong(1,town.town());s.executeUpdate();}
            changes.put(town.town(),amount);
        }
        return Map.copyOf(changes);
    }
}
