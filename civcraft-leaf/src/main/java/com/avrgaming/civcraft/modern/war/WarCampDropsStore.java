package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.util.*;
/** Inventory evacuation is prepared before world changes and committed after the world flush. */
public final class WarCampDropsStore {
    private WarCampDropsStore() {}
    public record Drop(String id,long camp,String world,int x,int y,int z,byte[] items,String state,boolean released) {
        public Drop(String id,long camp,String world,int x,int y,int z,byte[] items,String state){this(id,camp,world,x,y,z,items,state,false);}
    }
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS war_camp_drops(id TEXT PRIMARY KEY,camp_id INTEGER NOT NULL REFERENCES war_camps(id),world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,items BLOB NOT NULL,state TEXT NOT NULL CHECK(state IN ('PREPARED','DONE')),UNIQUE(camp_id,world,x,y,z))");boolean released=false;try(var r=s.executeQuery("PRAGMA table_info(war_camp_drops)")){while(r.next())if(r.getString("name").equals("released"))released=true;}if(!released)s.executeUpdate("ALTER TABLE war_camp_drops ADD COLUMN released INTEGER NOT NULL DEFAULT 0");}
    }
    public static List<Drop> list(Connection db,long camp)throws SQLException {
        var out=new ArrayList<Drop>();try(var s=db.prepareStatement("SELECT * FROM war_camp_drops WHERE camp_id=?")){s.setLong(1,camp);try(var r=s.executeQuery()){while(r.next())out.add(new Drop(r.getString("id"),camp,r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getBytes("items"),r.getString("state"),r.getBoolean("released")));}}return List.copyOf(out);
    }
    public static Drop prepare(Connection db,long camp,String world,int x,int y,int z,byte[] items)throws SQLException {
        try(var s=db.prepareStatement("SELECT * FROM war_camp_drops WHERE camp_id=? AND world=? AND x=? AND y=? AND z=?")){s.setLong(1,camp);s.setString(2,world);s.setInt(3,x);s.setInt(4,y);s.setInt(5,z);try(var r=s.executeQuery()){if(r.next())return new Drop(r.getString("id"),camp,world,x,y,z,r.getBytes("items"),r.getString("state"),r.getBoolean("released"));}}
        if(!WarCampStore.camp(db,camp).state().equals("RESTORING"))throw new IllegalArgumentException("Camp is not restoring");
        String id=UUID.randomUUID().toString();try(var s=db.prepareStatement("INSERT INTO war_camp_drops(id,camp_id,world,x,y,z,items,state) VALUES(?,?,?,?,?,?,?,'PREPARED')")){s.setString(1,id);s.setLong(2,camp);s.setString(3,world);s.setInt(4,x);s.setInt(5,y);s.setInt(6,z);s.setBytes(7,items);s.executeUpdate();}return new Drop(id,camp,world,x,y,z,items.clone(),"PREPARED");
    }
    public static void finish(Connection db,long camp)throws SQLException {
        try(var s=db.prepareStatement("UPDATE war_camp_drops SET state='DONE' WHERE camp_id=? AND state='PREPARED'")){s.setLong(1,camp);s.executeUpdate();}
    }
    public static List<Long> unreleasedCamps(Connection db)throws SQLException {
        var ids=new ArrayList<Long>();try(var s=db.createStatement();var r=s.executeQuery("SELECT DISTINCT camp_id FROM war_camp_drops WHERE state='DONE' AND released=0")){while(r.next())ids.add(r.getLong(1));}return List.copyOf(ids);
    }
    public static void release(Connection db,long camp)throws SQLException {
        try(var s=db.prepareStatement("UPDATE war_camp_drops SET released=1 WHERE camp_id=? AND state='DONE'")){s.setLong(1,camp);s.executeUpdate();}
    }
}
