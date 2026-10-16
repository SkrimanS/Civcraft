package com.avrgaming.civcraft.modern.trade;

import java.sql.*;

/** A horse delivery belongs to the same escrow receipt and player checkpoint as ordinary purchases. */
public final class StableStore {
    private StableStore() {}
    public record Delivery(String trade,long build,long town,String owner,String world,double x,double y,double z,double speed,double jump,double health,String color,String name,boolean mule,String entity) {}
    public static void install(Connection db)throws SQLException{
        try(var s=db.createStatement()){
            s.executeUpdate("CREATE TABLE IF NOT EXISTS stable_settings(town_id INTEGER PRIMARY KEY REFERENCES towns(id) ON DELETE CASCADE,fee REAL NOT NULL DEFAULT 0.05 CHECK(fee BETWEEN 0.05 AND 1))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS stable_selection(build_id INTEGER NOT NULL REFERENCES structure_builds(id) ON DELETE CASCADE,group_id INTEGER NOT NULL,selection INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(build_id,group_id))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS stable_deliveries(trade_id TEXT PRIMARY KEY REFERENCES trade_receipts(id),build_id INTEGER NOT NULL,town_id INTEGER NOT NULL,owner TEXT NOT NULL,world TEXT NOT NULL,x REAL NOT NULL,y REAL NOT NULL,z REAL NOT NULL,speed REAL NOT NULL,jump REAL NOT NULL,health REAL NOT NULL,color TEXT NOT NULL,name TEXT NOT NULL,mule INTEGER NOT NULL,entity_uuid TEXT)");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS stable_delivery_blocks_delete BEFORE DELETE ON structure_builds WHEN EXISTS(SELECT 1 FROM stable_deliveries d JOIN trade_receipts r ON r.id=d.trade_id WHERE d.build_id=OLD.id AND r.status='PREPARED') BEGIN SELECT RAISE(ABORT,'Stable delivery is pending'); END");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS stable_delivery_blocks_owner BEFORE UPDATE OF town_id,status ON structure_builds WHEN (NEW.town_id IS NOT OLD.town_id OR NEW.status IS NOT OLD.status) AND EXISTS(SELECT 1 FROM stable_deliveries d JOIN trade_receipts r ON r.id=d.trade_id WHERE d.build_id=OLD.id AND r.status='PREPARED') BEGIN SELECT RAISE(ABORT,'Stable delivery is pending'); END");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS stable_delivery_blocks_civ BEFORE UPDATE OF civ_id ON towns WHEN NEW.civ_id IS NOT OLD.civ_id AND EXISTS(SELECT 1 FROM stable_deliveries d JOIN trade_receipts r ON r.id=d.trade_id WHERE d.town_id=OLD.id AND r.status='PREPARED') BEGIN SELECT RAISE(ABORT,'Stable delivery is pending'); END");
        }
    }
    public static int selection(Connection db,long build,int group)throws SQLException{
        try(var s=db.prepareStatement("SELECT selection FROM stable_selection WHERE build_id=? AND group_id=?")){s.setLong(1,build);s.setInt(2,group);try(var r=s.executeQuery()){return r.next()?r.getInt(1):0;}}
    }
    public static void select(Connection db,long build,int group,int value)throws SQLException{
        try(var s=db.prepareStatement("INSERT INTO stable_selection VALUES(?,?,?) ON CONFLICT(build_id,group_id) DO UPDATE SET selection=excluded.selection")){s.setLong(1,build);s.setInt(2,group);s.setInt(3,value);s.executeUpdate();}
    }
    public static double fee(Connection db,long town)throws SQLException{
        try(var s=db.prepareStatement("SELECT fee FROM stable_settings WHERE town_id=?")){s.setLong(1,town);try(var r=s.executeQuery()){return r.next()?r.getDouble(1):.05;}}
    }
    public static void fee(Connection db,long town,double fee)throws SQLException{
        if(!Double.isFinite(fee) || fee<.05 || fee>1)throw new IllegalArgumentException("Комиссия конюшни: от 5 до 100%.");
        try(var s=db.prepareStatement("INSERT INTO stable_settings VALUES(?,?) ON CONFLICT(town_id) DO UPDATE SET fee=excluded.fee")){s.setLong(1,town);s.setDouble(2,fee);s.executeUpdate();}
    }
    public static void prepare(Connection db,String trade,long build,long town,String owner,String world,double x,double y,double z,StableCatalog.Horse horse)throws SQLException{
        if(db.getAutoCommit())throw new SQLException("Stable delivery requires a transaction");
        try(var s=db.prepareStatement("INSERT INTO stable_deliveries VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL)")){s.setString(1,trade);s.setLong(2,build);s.setLong(3,town);s.setString(4,owner);s.setString(5,world);s.setDouble(6,x);s.setDouble(7,y);s.setDouble(8,z);s.setDouble(9,horse.speed());s.setDouble(10,horse.jump());s.setDouble(11,horse.health());s.setString(12,horse.color());s.setString(13,horse.name());s.setBoolean(14,horse.mule());s.executeUpdate();}
    }
    public static Delivery delivery(Connection db,String trade)throws SQLException{
        try(var s=db.prepareStatement("SELECT * FROM stable_deliveries WHERE trade_id=?")){s.setString(1,trade);try(var r=s.executeQuery()){return r.next()?new Delivery(trade,r.getLong("build_id"),r.getLong("town_id"),r.getString("owner"),r.getString("world"),r.getDouble("x"),r.getDouble("y"),r.getDouble("z"),r.getDouble("speed"),r.getDouble("jump"),r.getDouble("health"),r.getString("color"),r.getString("name"),r.getBoolean("mule"),r.getString("entity_uuid")):null;}}
    }
    public static void saved(Connection db,String trade,String uuid)throws SQLException{
        try(var s=db.prepareStatement("UPDATE stable_deliveries SET entity_uuid=? WHERE trade_id=?")){s.setString(1,uuid);s.setString(2,trade);if(s.executeUpdate()!=1)throw new SQLException("Missing stable delivery");}
    }
}
