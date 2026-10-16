package com.avrgaming.civcraft.modern.trade;

import java.sql.*;
import java.util.*;

/** A departure changes canonical ownership before the player's old item can become a drop. */
public final class GoodieDepartureStore {
    private GoodieDepartureStore() {}
    public record Job(String id,UUID player,BonusGoodieStore.State good,GoodieTransferStore.Drop at,String phase) {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS goodie_departures(player_uuid TEXT PRIMARY KEY,world TEXT NOT NULL,x REAL NOT NULL,y REAL NOT NULL,z REAL NOT NULL,reason TEXT NOT NULL,created_at INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS goodie_departure_items(id TEXT PRIMARY KEY,player_uuid TEXT NOT NULL,origin_build INTEGER NOT NULL REFERENCES structure_builds(id) ON DELETE CASCADE,good_id TEXT NOT NULL,token TEXT NOT NULL,revision INTEGER NOT NULL,world TEXT NOT NULL,x REAL NOT NULL,y REAL NOT NULL,z REAL NOT NULL,phase TEXT NOT NULL CHECK(phase IN ('PREPARED','ACTIVE','DONE')))");
        }
    }
    public static List<UUID> requested(Connection db)throws SQLException {
        var out=new ArrayList<UUID>();try(var s=db.createStatement();var r=s.executeQuery("SELECT player_uuid FROM goodie_departures ORDER BY created_at,player_uuid")){while(r.next())out.add(UUID.fromString(r.getString(1)));}return out;
    }
    private static List<BonusGoodieStore.State> carried(Connection db,UUID player)throws SQLException {
        var ids=new ArrayList<Long>();try(var s=db.prepareStatement("SELECT origin_build FROM bonus_goodies WHERE holder_kind='PLAYER' AND holder_id=? ORDER BY origin_build")){s.setString(1,player.toString());try(var r=s.executeQuery()){while(r.next())ids.add(r.getLong(1));}}
        var out=new ArrayList<BonusGoodieStore.State>();for(long id:ids)out.add(BonusGoodieStore.origin(db,id));return out;
    }
    public static boolean waiting(Connection db,UUID player)throws SQLException {
        if(GoodieChestStore.pendingPlayer(db,player))return true;
        try(var s=db.prepareStatement("SELECT 1 FROM trade_receipts WHERE player_uuid=? AND status='PREPARED' UNION ALL SELECT 1 FROM goodie_transfers WHERE player_uuid=? AND status='PREPARED'")){s.setString(1,player.toString());s.setString(2,player.toString());try(var r=s.executeQuery()){return r.next();}}
    }
    public static boolean request(Connection db,UUID player,GoodieTransferStore.Drop at,String reason,long now)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Goodie departure requires a transaction");
        if(carried(db,player).isEmpty() && !waiting(db,player))return false;
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO goodie_departures VALUES(?,?,?,?,?,?,?)")){s.setString(1,player.toString());s.setString(2,at.world());s.setDouble(3,at.x());s.setDouble(4,at.y());s.setDouble(5,at.z());s.setString(6,reason);s.setLong(7,now);s.executeUpdate();}return true;
    }
    public static boolean process(Connection db,UUID player)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Goodie departure requires a transaction");
        if(waiting(db,player))return false;
        GoodieTransferStore.Drop at;
        try(var s=db.prepareStatement("SELECT world,x,y,z FROM goodie_departures WHERE player_uuid=?")){s.setString(1,player.toString());try(var r=s.executeQuery()){if(!r.next())return true;at=new GoodieTransferStore.Drop(r.getString(1),r.getDouble(2),r.getDouble(3),r.getDouble(4));}}
        for(var before:carried(db,player)) {
            GoodieTransferStore.requireOriginUnlocked(db,before.origin());String id=UUID.randomUUID().toString();
            var after=BonusGoodieStore.move(db,before,BonusGoodieStore.Kind.ITEM,id,null);
            try(var s=db.prepareStatement("INSERT INTO goodie_departure_items VALUES(?,?,?,?,?,?,?,?,?,?,'PREPARED')")) {
                s.setString(1,id);s.setString(2,player.toString());s.setLong(3,after.origin());s.setString(4,after.good());s.setString(5,after.token());s.setLong(6,after.revision());s.setString(7,at.world());s.setDouble(8,at.x());s.setDouble(9,at.y());s.setDouble(10,at.z());s.executeUpdate();
            }
        }
        try(var s=db.prepareStatement("DELETE FROM goodie_departures WHERE player_uuid=?")){s.setString(1,player.toString());s.executeUpdate();}return true;
    }
    public static List<Job> jobs(Connection db)throws SQLException {
        var out=new ArrayList<Job>();try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM goodie_departure_items WHERE phase<>'DONE' ORDER BY rowid")) {
            while(r.next()) {
                String id=r.getString("id");var good=new BonusGoodieStore.State(r.getLong("origin_build"),r.getString("good_id"),r.getString("token"),r.getLong("revision"),BonusGoodieStore.Kind.ITEM,id,null);
                out.add(new Job(id,UUID.fromString(r.getString("player_uuid")),good,new GoodieTransferStore.Drop(r.getString("world"),r.getDouble("x"),r.getDouble("y"),r.getDouble("z")),r.getString("phase")));
            }
        }return out;
    }
    public static void phase(Connection db,String id,String phase)throws SQLException {
        if(!Set.of("ACTIVE","DONE").contains(phase))throw new IllegalArgumentException("Invalid departure phase");
        try(var s=db.prepareStatement("UPDATE goodie_departure_items SET phase=? WHERE id=?")){s.setString(1,phase);s.setString(2,id);s.executeUpdate();}
    }
}
