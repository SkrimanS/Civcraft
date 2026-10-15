package com.avrgaming.civcraft.modern.trade;

import java.sql.*;
import java.util.*;

public final class TradeGenerationStore {
    private TradeGenerationStore() {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS trade_generation_worlds(world TEXT PRIMARY KEY,seed INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS trade_generation_plan(world TEXT NOT NULL REFERENCES trade_generation_worlds(world) ON DELETE CASCADE,chunk_x INTEGER NOT NULL,chunk_z INTEGER NOT NULL,land_good TEXT NOT NULL,water_good TEXT NOT NULL,PRIMARY KEY(world,chunk_x,chunk_z))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS trade_generation_requests(world TEXT NOT NULL,chunk_x INTEGER NOT NULL,chunk_z INTEGER NOT NULL,PRIMARY KEY(world,chunk_x,chunk_z),FOREIGN KEY(world,chunk_x,chunk_z) REFERENCES trade_generation_plan(world,chunk_x,chunk_z) ON DELETE CASCADE)");
        }
    }
    public static boolean initialized(Connection db,String world)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM trade_generation_worlds WHERE world=?")){s.setString(1,world);try(var r=s.executeQuery()){return r.next();}}
    }
    public static boolean initialize(Connection db,String world,int seed,Map<TradeGoodGeneration.Chunk,TradeGoodGeneration.Pick> plan)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Trade generation planning requires a transaction");
        if(initialized(db,world))return false;
        try(var s=db.prepareStatement("INSERT INTO trade_generation_worlds VALUES(?,?)")){s.setString(1,world);s.setInt(2,seed);s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO trade_generation_plan VALUES(?,?,?,?,?)")) {
            for(var pick:plan.values()){s.setString(1,world);s.setInt(2,pick.chunk().x());s.setInt(3,pick.chunk().z());s.setString(4,pick.land());s.setString(5,pick.water());s.addBatch();}s.executeBatch();
        }
        return true;
    }
    public static TradeGoodGeneration.Pick pick(Connection db,String world,int x,int z)throws SQLException {
        try(var s=db.prepareStatement("SELECT land_good,water_good FROM trade_generation_plan WHERE world=? AND chunk_x=? AND chunk_z=?")) {
            s.setString(1,world);s.setInt(2,x);s.setInt(3,z);try(var r=s.executeQuery()){return r.next()?new TradeGoodGeneration.Pick(new TradeGoodGeneration.Chunk(x,z),r.getString(1),r.getString(2)):null;}
        }
    }
    public static void request(Connection db,String world,int x,int z)throws SQLException {
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO trade_generation_requests SELECT world,chunk_x,chunk_z FROM trade_generation_plan WHERE world=? AND chunk_x=? AND chunk_z=?")) {
            s.setString(1,world);s.setInt(2,x);s.setInt(3,z);s.executeUpdate();
        }
    }
    public static List<TradeGoodGeneration.Chunk> requests(Connection db,String world)throws SQLException {
        var result=new ArrayList<TradeGoodGeneration.Chunk>();
        try(var s=db.prepareStatement("SELECT chunk_x,chunk_z FROM trade_generation_requests WHERE world=? ORDER BY chunk_x,chunk_z")) {
            s.setString(1,world);try(var r=s.executeQuery()){while(r.next())result.add(new TradeGoodGeneration.Chunk(r.getInt(1),r.getInt(2)));}
        }return List.copyOf(result);
    }
    public static void removeRequest(Connection db,String world,TradeGoodGeneration.Chunk chunk)throws SQLException {
        try(var s=db.prepareStatement("DELETE FROM trade_generation_requests WHERE world=? AND chunk_x=? AND chunk_z=?")){s.setString(1,world);s.setInt(2,chunk.x());s.setInt(3,chunk.z());s.executeUpdate();}
    }
}
