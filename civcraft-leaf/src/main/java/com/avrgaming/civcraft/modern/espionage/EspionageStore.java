package com.avrgaming.civcraft.modern.espionage;

import java.sql.*;
import java.util.*;

public final class EspionageStore {
    private EspionageStore() {}
    public record Run(String id,UUID player,long source,long target,long targetCiv,String mission,int remaining) {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS spy_exposure(player TEXT PRIMARY KEY REFERENCES residents(uuid) ON DELETE CASCADE,amount REAL NOT NULL DEFAULT 0 CHECK(amount>=0))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS spy_missions(id TEXT PRIMARY KEY,player TEXT NOT NULL,source_town INTEGER NOT NULL,target_town INTEGER NOT NULL,target_civ INTEGER NOT NULL,mission TEXT NOT NULL,remaining INTEGER NOT NULL CHECK(remaining>=0),state TEXT NOT NULL CHECK(state IN ('ACTIVE','SUCCESS','FAILED','ABORTED')),result TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL,finished_at INTEGER)");
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_active_spy_mission ON spy_missions(player) WHERE state='ACTIVE'");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS spy_history_source ON spy_missions(source_town,created_at)");
        }
    }
    public static List<Run> active(Connection db)throws SQLException {
        var result=new ArrayList<Run>();try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM spy_missions WHERE state='ACTIVE'")){while(r.next())result.add(new Run(r.getString("id"),UUID.fromString(r.getString("player")),r.getLong("source_town"),r.getLong("target_town"),r.getLong("target_civ"),r.getString("mission"),r.getInt("remaining")));}return result;
    }
    public static void start(Connection db,Run run,long now)throws SQLException {
        try(var s=db.prepareStatement("INSERT INTO spy_missions(id,player,source_town,target_town,target_civ,mission,remaining,state,created_at) VALUES(?,?,?,?,?,?,?,'ACTIVE',?)")){s.setString(1,run.id());s.setString(2,run.player().toString());s.setLong(3,run.source());s.setLong(4,run.target());s.setLong(5,run.targetCiv());s.setString(6,run.mission());s.setInt(7,run.remaining());s.setLong(8,now);s.executeUpdate();}
    }
    public static double exposure(Connection db,UUID player,double add)throws SQLException {
        if(!Double.isFinite(add) || add<0)throw new IllegalArgumentException("Invalid exposure");
        try(var s=db.prepareStatement("INSERT INTO spy_exposure VALUES(?,?) ON CONFLICT(player) DO UPDATE SET amount=amount+excluded.amount")){s.setString(1,player.toString());s.setDouble(2,add);s.executeUpdate();}
        try(var s=db.prepareStatement("SELECT amount FROM spy_exposure WHERE player=?")){s.setString(1,player.toString());try(var r=s.executeQuery()){r.next();return r.getDouble(1);}}
    }
    public static void decay(Connection db)throws SQLException {try(var s=db.createStatement()){s.executeUpdate("UPDATE spy_exposure SET amount=MAX(0,amount-5) WHERE amount>0 AND NOT EXISTS(SELECT 1 FROM spy_missions m WHERE m.player=spy_exposure.player AND m.state='ACTIVE')");}}
    public static int step(Connection db,Run run)throws SQLException {
        int left=Math.max(0,run.remaining()-1);try(var s=db.prepareStatement("UPDATE spy_missions SET remaining=? WHERE id=? AND state='ACTIVE' AND remaining=?")){s.setInt(1,left);s.setString(2,run.id());s.setInt(3,run.remaining());if(s.executeUpdate()!=1)throw new SQLException("Mission changed during countdown");}return left;
    }
    public static void finish(Connection db,String id,String state,String result,long now)throws SQLException {
        if(!Set.of("SUCCESS","FAILED","ABORTED").contains(state))throw new IllegalArgumentException("Invalid mission result");
        try(var s=db.prepareStatement("UPDATE spy_missions SET state=?,result=?,finished_at=? WHERE id=? AND state='ACTIVE'")){s.setString(1,state);s.setString(2,result);s.setLong(3,now);s.setString(4,id);if(s.executeUpdate()!=1)throw new SQLException("Mission is no longer active");}
    }
}
