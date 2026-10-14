package com.avrgaming.civcraft.modern.trade;
import java.sql.*;
public final class GrocerStore {
    private GrocerStore() {}
    public record Settings(int level,double fee) {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS town_grocer_settings(town_id INTEGER PRIMARY KEY REFERENCES towns(id) ON DELETE CASCADE,level INTEGER NOT NULL DEFAULT 1 CHECK(level BETWEEN 1 AND 4),fee REAL NOT NULL DEFAULT 0.05 CHECK(fee BETWEEN 0 AND 1))");}
    }
    public static Settings settings(Connection db,long town)throws SQLException {
        try(var s=db.prepareStatement("SELECT level,fee FROM town_grocer_settings WHERE town_id=?")) {
            s.setLong(1,town);try(var r=s.executeQuery()){return r.next()?new Settings(r.getInt(1),r.getDouble(2)):new Settings(1,.05);}
        }
    }
    public static void level(Connection db,long town,int level)throws SQLException {
        if(level<1 || level>4)throw new IllegalArgumentException("Некорректный уровень бакалеи.");
        try(var s=db.prepareStatement("INSERT INTO town_grocer_settings(town_id,level) VALUES(?,?) ON CONFLICT(town_id) DO UPDATE SET level=MAX(level,excluded.level)")){s.setLong(1,town);s.setInt(2,level);s.executeUpdate();}
    }
}
