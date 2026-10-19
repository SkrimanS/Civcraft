package com.avrgaming.civcraft.modern.town;
import java.sql.*;
/** Original poison duration counts successful cottage meals, shared by the town. */
public final class GranaryPoisonStore {
    private GranaryPoisonStore() {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS granary_poison(town_id INTEGER PRIMARY KEY REFERENCES towns(id) ON DELETE CASCADE,ticks INTEGER NOT NULL CHECK(ticks>=0))");}
    }
    public static int remaining(Connection db,long town)throws SQLException {
        try(var s=db.prepareStatement("SELECT ticks FROM granary_poison WHERE town_id=?")) {
            s.setLong(1,town);try(var r=s.executeQuery()){return r.next()?r.getInt(1):0;}
        }
    }
    /** Overlapping missions retain the longest duration, as old session entries did. */
    public static void apply(Connection db,long town,int ticks)throws SQLException {
        if(ticks<1)throw new IllegalArgumentException("Неверная длительность отравления");
        try(var s=db.prepareStatement("INSERT INTO granary_poison VALUES(?,?) ON CONFLICT(town_id) DO UPDATE SET ticks=MAX(ticks,excluded.ticks)")) {
            s.setLong(1,town);s.setInt(2,ticks);s.executeUpdate();
        }
    }
    /** Reserve a poisoned meal atomically with its consumption receipt. */
    public static void reserve(Connection db,long town)throws SQLException {
        try(var s=db.prepareStatement("UPDATE granary_poison SET ticks=ticks-1 WHERE town_id=? AND ticks>0")) {
            s.setLong(1,town);if(s.executeUpdate()!=1)throw new SQLException("Granary poison changed before meal preparation");
        }
    }
}
