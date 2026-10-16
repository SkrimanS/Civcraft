package com.avrgaming.civcraft.modern.trade;

import java.sql.*;
import java.util.*;

/** Session mutations are committed together with a TradeStore inventory intent. */
public final class BlacksmithStore {
    private BlacksmithStore() {}
    public record Smelt(long id,int item,int amount,long ready) {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS blacksmith_catalysts(build_id INTEGER NOT NULL REFERENCES structure_builds(id) ON DELETE CASCADE,player TEXT NOT NULL,catalyst TEXT NOT NULL,PRIMARY KEY(build_id,player))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS blacksmith_smelting(id INTEGER PRIMARY KEY AUTOINCREMENT,build_id INTEGER NOT NULL REFERENCES structure_builds(id) ON DELETE CASCADE,player TEXT NOT NULL,item INTEGER NOT NULL CHECK(item IN (265,266)),amount INTEGER NOT NULL CHECK(amount>0),ready_at INTEGER NOT NULL)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS blacksmith_smelting_owner ON blacksmith_smelting(build_id,player,id)");
        }
    }
    public static String catalyst(Connection db,long build,String player)throws SQLException {
        try(var s=db.prepareStatement("SELECT catalyst FROM blacksmith_catalysts WHERE build_id=? AND player=?")){s.setLong(1,build);s.setString(2,player);try(var r=s.executeQuery()){return r.next()?r.getString(1):null;}}
    }
    public static void catalyst(Connection db,long build,String player,String catalyst)throws SQLException {
        if(catalyst==null){try(var s=db.prepareStatement("DELETE FROM blacksmith_catalysts WHERE build_id=? AND player=?")){s.setLong(1,build);s.setString(2,player);s.executeUpdate();}}
        else try(var s=db.prepareStatement("INSERT INTO blacksmith_catalysts VALUES(?,?,?)")){s.setLong(1,build);s.setString(2,player);s.setString(3,catalyst);s.executeUpdate();}
    }
    public static void deposit(Connection db,long build,String player,int item,int amount,long now)throws SQLException {
        try(var s=db.prepareStatement("INSERT INTO blacksmith_smelting(build_id,player,item,amount,ready_at) VALUES(?,?,?,?,?)")){s.setLong(1,build);s.setString(2,player);s.setInt(3,item);s.setInt(4,amount);s.setLong(5,Math.addExact(now,10_800_000L));s.executeUpdate();}
    }
    public static List<Smelt> smelting(Connection db,long build,String player)throws SQLException {
        var result=new ArrayList<Smelt>();try(var s=db.prepareStatement("SELECT id,item,amount,ready_at FROM blacksmith_smelting WHERE build_id=? AND player=? ORDER BY id")){s.setLong(1,build);s.setString(2,player);try(var r=s.executeQuery()){while(r.next())result.add(new Smelt(r.getLong(1),r.getInt(2),r.getInt(3),r.getLong(4)));}}return result;
    }
    public static void withdraw(Connection db,long id,int remaining)throws SQLException {
        if(remaining==0){try(var s=db.prepareStatement("DELETE FROM blacksmith_smelting WHERE id=?")){s.setLong(1,id);s.executeUpdate();}}
        else try(var s=db.prepareStatement("UPDATE blacksmith_smelting SET amount=? WHERE id=?")){s.setInt(1,remaining);s.setLong(2,id);s.executeUpdate();}
    }
}
