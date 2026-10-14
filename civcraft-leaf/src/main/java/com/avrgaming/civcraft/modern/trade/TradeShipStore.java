package com.avrgaming.civcraft.modern.trade;

import java.sql.*;

public final class TradeShipStore {
    private TradeShipStore() {}
    public static void install(Connection db) throws SQLException {
        TradeShipIncomeStore.install(db);
        try (var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS town_trade_ship_settings(town_id INTEGER PRIMARY KEY REFERENCES towns(id) ON DELETE CASCADE,upgrade_level INTEGER NOT NULL DEFAULT 1 CHECK(upgrade_level BETWEEN 1 AND 6))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS trade_ship_progress(build_id INTEGER PRIMARY KEY REFERENCES structure_builds(id) ON DELETE CASCADE,level INTEGER NOT NULL DEFAULT 1 CHECK(level BETWEEN 1 AND 10),trade_count INTEGER NOT NULL DEFAULT 0 CHECK(trade_count>=0))");
        }
    }
    public static int upgrade(Connection db,long town) throws SQLException {
        try(var s=db.prepareStatement("SELECT upgrade_level FROM town_trade_ship_settings WHERE town_id=?")) {
            s.setLong(1,town);try(var r=s.executeQuery()){return r.next()?r.getInt(1):1;}
        }
    }
    public static void upgrade(Connection db,long town,int level) throws SQLException {
        if(level<1 || level>6) throw new IllegalArgumentException("Некорректный уровень улучшения торгового корабля.");
        try(var s=db.prepareStatement("INSERT INTO town_trade_ship_settings VALUES(?,?) ON CONFLICT(town_id) DO UPDATE SET upgrade_level=MAX(upgrade_level,excluded.upgrade_level)")) {
            s.setLong(1,town);s.setInt(2,level);s.executeUpdate();
        }
    }
    public static TradeShipProgress.State progress(Connection db,long build) throws SQLException {
        try(var s=db.prepareStatement("SELECT level,trade_count FROM trade_ship_progress WHERE build_id=?")) {
            s.setLong(1,build);try(var r=s.executeQuery()){return r.next()?new TradeShipProgress.State(r.getInt(1),r.getInt(2)):new TradeShipProgress.State(1,0);}
        }
    }
    public static void progress(Connection db,long build,TradeShipProgress.State state) throws SQLException {
        try(var s=db.prepareStatement("INSERT INTO trade_ship_progress VALUES(?,?,?) ON CONFLICT(build_id) DO UPDATE SET level=excluded.level,trade_count=excluded.trade_count")) {
            s.setLong(1,build);s.setInt(2,state.level());s.setInt(3,state.count());s.executeUpdate();
        }
    }
}
