package com.avrgaming.civcraft.modern.trade;

import java.sql.*;
import java.util.*;

public final class TradeMarkerStore {
    private TradeMarkerStore() {}
    public record Job(long deposit,String good,String world,int x,int y,int z,int face) {}
    public record Position(String world,int x,int y,int z) {}
    public static void install(Connection db)throws SQLException {
        TradeDepositStore.install(db);TradeGenerationStore.install(db);
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS trade_marker_jobs(deposit_id INTEGER PRIMARY KEY REFERENCES trade_deposits(id) ON DELETE CASCADE,face INTEGER NOT NULL CHECK(face BETWEEN 0 AND 3),state TEXT NOT NULL CHECK(state IN ('PREPARED','COMPLETE')))");
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_trade_deposit_column ON trade_deposits(world,x,z)");
        }
    }
    public static Job prepare(Connection db,TradeGoodsCatalog catalog,String good,String world,int x,int y,int z,int face)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Trade marker creation requires a transaction");
        if(face<0||face>3)throw new IllegalArgumentException("Неверное направление торгового маркера");
        try(var s=db.prepareStatement("SELECT 1 FROM trade_deposits WHERE world=? AND x=? AND z=?")) {
            s.setString(1,world);s.setInt(2,x);s.setInt(3,z);try(var r=s.executeQuery()){if(r.next())return null;}
        }
        TradeDepositStore.register(db,catalog,good,world,x,y,z);long id;
        try(var s=db.createStatement();var r=s.executeQuery("SELECT last_insert_rowid()")){r.next();id=r.getLong(1);}
        try(var s=db.prepareStatement("INSERT INTO trade_marker_jobs VALUES(?,?,'PREPARED')")){s.setLong(1,id);s.setInt(2,face);s.executeUpdate();}
        return new Job(id,good,world,x,y,z,face);
    }
    public static List<Position> positions(Job job) {
        int dx=switch(job.face()){case 1->1;case 3->-1;default->0;},dz=switch(job.face()){case 0->-1;case 2->1;default->0;};
        return List.of(new Position(job.world(),job.x(),job.y(),job.z()),new Position(job.world(),job.x(),job.y()+1,job.z()),
                new Position(job.world(),job.x(),job.y()+2,job.z()),new Position(job.world(),job.x()+dx,job.y()+2,job.z()+dz));
    }
    public static List<Job> jobs(Connection db,boolean pendingOnly)throws SQLException {
        var result=new ArrayList<Job>();try(var s=db.createStatement();var r=s.executeQuery("SELECT d.*,j.face FROM trade_marker_jobs j JOIN trade_deposits d ON d.id=j.deposit_id"+(pendingOnly?" WHERE j.state='PREPARED'":"")+" ORDER BY d.id")) {
            while(r.next())result.add(new Job(r.getLong("id"),r.getString("good_id"),r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getInt("face")));
        }return List.copyOf(result);
    }
    public static Job completed(Connection db,long deposit)throws SQLException {
        try(var s=db.prepareStatement("SELECT d.*,j.face FROM trade_marker_jobs j JOIN trade_deposits d ON d.id=j.deposit_id WHERE d.id=? AND j.state='COMPLETE'")) {
            s.setLong(1,deposit);try(var r=s.executeQuery()){return r.next()?new Job(r.getLong("id"),r.getString("good_id"),r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getInt("face")):null;}
        }
    }
    public static void complete(Connection db,long deposit)throws SQLException {
        try(var s=db.prepareStatement("UPDATE trade_marker_jobs SET state='COMPLETE' WHERE deposit_id=?")){s.setLong(1,deposit);if(s.executeUpdate()!=1)throw new SQLException("Trade marker job missing");}
    }
}
