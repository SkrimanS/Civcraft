package com.avrgaming.civcraft.modern.war;

import java.sql.*;
import java.util.*;

/** Write-ahead world mutations and a single original snapshot per war/coordinate. */
public final class SiegeWorldStore {
    private SiegeWorldStore() {}
    public record Cell(int x,int y,int z,String before,String nbt,String after,String afterNbt,String role) {}
    public record Job(String id,long war,String world,String kind,String state) {}
    public record Owned(String cannon,String state,String role,String data) {}
    public record Position(int x,int y,int z) {}
    private static void transaction(Connection db)throws SQLException{if(db.getAutoCommit())throw new SQLException("Siege world mutation requires a transaction");}
    public static void install(Connection db)throws SQLException{
        try(var s=db.createStatement()){
            s.executeUpdate("CREATE TABLE IF NOT EXISTS siege_world_jobs(id TEXT PRIMARY KEY,war_start INTEGER NOT NULL,world TEXT NOT NULL,kind TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'PREPARED')");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS siege_world_cells(job_id TEXT NOT NULL REFERENCES siege_world_jobs(id),ordinal INTEGER NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,before_data TEXT NOT NULL,before_nbt TEXT,after_data TEXT NOT NULL,after_nbt TEXT,role TEXT NOT NULL,PRIMARY KEY(job_id,ordinal),UNIQUE(job_id,x,y,z))");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS siege_world_cell_position ON siege_world_cells(x,y,z)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS siege_original_terrain(war_start INTEGER NOT NULL,world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,block_data TEXT NOT NULL,block_nbt TEXT,PRIMARY KEY(war_start,world,x,y,z))");
        }
    }
    public static void prepare(Connection db,String id,long war,String world,String kind,List<Cell> cells,boolean remember)throws SQLException{
        transaction(db);
        for(var cell:cells)com.avrgaming.civcraft.modern.wall.WallStore.requireAreaFree(db,world,cell.x(),cell.y(),cell.z(),1,1,1);
        try(var s=db.prepareStatement("INSERT INTO siege_world_jobs(id,war_start,world,kind) VALUES(?,?,?,?)")){s.setString(1,id);s.setLong(2,war);s.setString(3,world);s.setString(4,kind);s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO siege_world_cells VALUES(?,?,?,?,?,?,?,?,?,?)");var original=db.prepareStatement("INSERT OR IGNORE INTO siege_original_terrain VALUES(?,?,?,?,?,?,?)")){
            int index=0;for(var c:cells){s.setString(1,id);s.setInt(2,index++);s.setInt(3,c.x());s.setInt(4,c.y());s.setInt(5,c.z());s.setString(6,c.before());s.setString(7,c.nbt());s.setString(8,c.after());s.setString(9,c.afterNbt());s.setString(10,c.role());
                s.addBatch();
                if(remember && (!c.before().equals(c.after()) || !Objects.equals(c.nbt(),c.afterNbt()))){original.setLong(1,war);original.setString(2,world);original.setInt(3,c.x());original.setInt(4,c.y());original.setInt(5,c.z());original.setString(6,c.before());original.setString(7,c.nbt());original.addBatch();}
            }
            s.executeBatch();if(remember)original.executeBatch();
        }
    }
    public static List<Cell> cells(Connection db,String id)throws SQLException{
        var cells=new ArrayList<Cell>();try(var s=db.prepareStatement("SELECT * FROM siege_world_cells WHERE job_id=? ORDER BY ordinal")){s.setString(1,id);try(var r=s.executeQuery()){while(r.next())cells.add(new Cell(r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getString("before_data"),r.getString("before_nbt"),r.getString("after_data"),r.getString("after_nbt"),r.getString("role")));}}return cells;
    }
    public static Job job(Connection db,String id)throws SQLException{
        try(var s=db.prepareStatement("SELECT * FROM siege_world_jobs WHERE id=?")){s.setString(1,id);try(var r=s.executeQuery()){return r.next()?new Job(id,r.getLong("war_start"),r.getString("world"),r.getString("kind"),r.getString("state")):null;}}
    }
    public static List<Job> pending(Connection db)throws SQLException{
        var jobs=new ArrayList<Job>();try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM siege_world_jobs WHERE state='PREPARED' ORDER BY rowid")){while(r.next())jobs.add(new Job(r.getString("id"),r.getLong("war_start"),r.getString("world"),r.getString("kind"),r.getString("state")));}return jobs;
    }
    public static void done(Connection db,String id)throws SQLException{
        LegacyExplosionStore.worldDone(db,id);
        transaction(db);try(var s=db.prepareStatement("UPDATE siege_world_jobs SET state='DONE' WHERE id=? AND state='PREPARED'")){s.setString(1,id);if(s.executeUpdate()!=1)throw new SQLException("Siege world job changed");}
    }
    public static boolean tradeComplete(Connection db,String id)throws SQLException{
        try(var s=db.prepareStatement("SELECT 1 FROM trade_receipts WHERE id=? AND status='COMPLETE'")){s.setString(1,id);try(var r=s.executeQuery()){return r.next();}}
    }
    public static boolean active(Connection db,long war,long now)throws SQLException{
        try(var s=db.prepareStatement("SELECT 1 FROM war_sessions WHERE start_at=? AND state='ACTIVE' AND start_at<=? AND end_at>?")){s.setLong(1,war);s.setLong(2,now);s.setLong(3,now);try(var r=s.executeQuery()){return r.next();}}
    }
    public static Owned owned(Connection db,String world,int x,int y,int z)throws SQLException{
        try(var s=db.prepareStatement("SELECT c.id,c.state,b.role,b.after_data FROM siege_world_cells b JOIN siege_cannons c ON c.id=b.job_id WHERE c.world=? AND b.x=? AND b.y=? AND b.z=? AND c.state IN ('PREPARED','ACTIVE','DESTROY_PENDING') ORDER BY c.rowid DESC LIMIT 1")){
            s.setString(1,world);s.setInt(2,x);s.setInt(3,y);s.setInt(4,z);try(var r=s.executeQuery()){return r.next()?new Owned(r.getString(1),r.getString(2),r.getString(3),r.getString(4)):null;}
        }
    }
    public static boolean locked(Connection db,String world,int x,int y,int z)throws SQLException{
        try(var s=db.prepareStatement("SELECT 1 FROM siege_world_cells c JOIN siege_world_jobs j ON j.id=c.job_id WHERE j.world=? AND c.x=? AND c.y=? AND c.z=? AND j.state='PREPARED' LIMIT 1")){
            s.setString(1,world);s.setInt(2,x);s.setInt(3,y);s.setInt(4,z);try(var r=s.executeQuery()){return r.next();}
        }
    }
    public static boolean retainedContainer(Connection db,String world,int x,int y,int z,long now)throws SQLException{
        try(var s=db.prepareStatement("SELECT 1 FROM siege_original_terrain t JOIN war_sessions w ON w.start_at=t.war_start WHERE t.world=? AND t.x=? AND t.y=? AND t.z=? AND t.block_nbt IS NOT NULL AND w.state='ACTIVE' AND w.end_at>? LIMIT 1")){
            s.setString(1,world);s.setInt(2,x);s.setInt(3,y);s.setInt(4,z);s.setLong(5,now);try(var r=s.executeQuery()){return r.next();}
        }
    }
    public static boolean installed(Connection db)throws SQLException{
        try(var s=db.createStatement();var r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='siege_world_jobs'")){return r.next();}
    }
    public static Set<Position> productionLocks(Connection db,String world)throws SQLException{
        var result=new HashSet<Position>();
        for(var table:List.of("farm_growth_cells","resource_production_plantings")){
            try(var s=db.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")){s.setString(1,table);try(var r=s.executeQuery()){if(!r.next())continue;}}
            try(var s=db.prepareStatement("SELECT c.x,c.y,c.z FROM "+table+" c JOIN resource_production_operations o ON o.id=c.operation_id WHERE o.status='PREPARED' AND c.world=?")){
                s.setString(1,world);try(var r=s.executeQuery()){while(r.next()){result.add(new Position(r.getInt(1),r.getInt(2),r.getInt(3)));result.add(new Position(r.getInt(1),r.getInt(2)-1,r.getInt(3)));}}
            }
        }return result;
    }
    public static void requireAreaFree(Connection db,String world,int x,int y,int z,int width,int height,int depth)throws SQLException{
        com.avrgaming.civcraft.modern.wall.WallStore.requireAreaFree(db,world,x,y,z,width,height,depth);
        if(!installed(db))return;
        // End-of-war restoration may not have been queued by its clock yet.
        // Do not admit a new structure into saved terrain in that interval.
        try(var s=db.prepareStatement("""
            SELECT 1 FROM siege_original_terrain t JOIN war_sessions w ON w.start_at=t.war_start
            WHERE t.world=? AND t.x BETWEEN ? AND ? AND t.y BETWEEN ? AND ? AND t.z BETWEEN ? AND ?
              AND (w.state<>'ACTIVE' OR w.end_at<=?) AND NOT EXISTS(
                SELECT 1 FROM siege_world_jobs j WHERE j.id='restore:'||t.war_start||':'||t.world AND j.state='DONE') LIMIT 1
            """)) {
            s.setString(1,world);s.setInt(2,x);s.setInt(3,Math.addExact(x,width-1));s.setInt(4,y);s.setInt(5,Math.addExact(y,height-1));s.setInt(6,z);s.setInt(7,Math.addExact(z,depth-1));s.setLong(8,System.currentTimeMillis());
            try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Эта местность ещё ожидает восстановления после войны.");}
        }
        String sql="SELECT 1 FROM siege_world_cells b JOIN siege_world_jobs j ON j.id=b.job_id LEFT JOIN siege_cannons c ON c.id=j.id WHERE j.world=? AND b.x BETWEEN ? AND ? AND b.y BETWEEN ? AND ? AND b.z BETWEEN ? AND ? AND (j.state='PREPARED' OR (c.state IN ('PREPARED','ACTIVE','DESTROY_PENDING') AND b.role<>'air')) LIMIT 1";
        try(var s=db.prepareStatement(sql)){
            s.setString(1,world);s.setInt(2,x);s.setInt(3,Math.addExact(x,width-1));s.setInt(4,y);s.setInt(5,Math.addExact(y,height-1));s.setInt(6,z);s.setInt(7,Math.addExact(z,depth-1));try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Осадная пушка или восстановление военной местности мешает строительству.");}
        }
    }
    /** Cancel unfinished effects, then restore the first snapshot, never a later cannon's rubble. */
    public static void expire(Connection db,long now)throws SQLException{
        transaction(db);record Region(long war,String world){}var regions=new ArrayList<Region>();
        try(var s=db.prepareStatement("SELECT DISTINCT t.war_start,t.world FROM (SELECT war_start,world FROM siege_original_terrain UNION SELECT war_start,world FROM siege_cannons UNION SELECT war_start,world FROM siege_world_jobs WHERE kind<>'RESTORE') t JOIN war_sessions w ON w.start_at=t.war_start WHERE w.state<>'ACTIVE' OR w.end_at<=?")){s.setLong(1,now);try(var r=s.executeQuery()){while(r.next())regions.add(new Region(r.getLong(1),r.getString(2)));}}
        for(var region:regions){String id="restore:"+region.war()+":"+region.world();if(job(db,id)!=null)continue;
            try(var s=db.prepareStatement("UPDATE siege_world_jobs SET state='CANCELLED' WHERE war_start=? AND world=? AND state='PREPARED'")){s.setLong(1,region.war());s.setString(2,region.world());s.executeUpdate();}
            try(var s=db.prepareStatement("UPDATE siege_cannons SET state='EXPIRED' WHERE war_start=? AND world=?")){s.setLong(1,region.war());s.setString(2,region.world());s.executeUpdate();}
            try(var s=db.prepareStatement("UPDATE siege_cannon_shots SET state='CANCELLED' WHERE cannon_id IN (SELECT id FROM siege_cannons WHERE war_start=? AND world=?) AND state NOT IN ('DONE','CANCELLED')")){s.setLong(1,region.war());s.setString(2,region.world());s.executeUpdate();}
            try(var s=db.prepareStatement("UPDATE siege_player_hits SET state='CANCELLED' WHERE state='PREPARED' AND id IN (SELECT s.id FROM siege_cannon_shots s JOIN siege_cannons c ON c.id=s.cannon_id WHERE c.war_start=? AND c.world=?)")){s.setLong(1,region.war());s.setString(2,region.world());s.executeUpdate();}
            var cells=new ArrayList<Cell>();try(var s=db.prepareStatement("SELECT * FROM siege_original_terrain WHERE war_start=? AND world=? ORDER BY y,x,z")){s.setLong(1,region.war());s.setString(2,region.world());try(var r=s.executeQuery()){while(r.next())cells.add(new Cell(r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getString("block_data"),r.getString("block_nbt"),r.getString("block_data"),r.getString("block_nbt"),"restore"));}}
            prepare(db,id,region.war(),region.world(),"RESTORE",cells,false);
        }
    }
}
