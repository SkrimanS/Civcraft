package com.avrgaming.civcraft.modern.trade;

import java.sql.*;
import java.util.*;

public final class GoodieFrameStore {
    private GoodieFrameStore() {}
    public record Frame(String key,long build,int slot,String world,int x,int y,int z,String facing,boolean source,String entity) {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()){s.executeUpdate("""
            CREATE TABLE IF NOT EXISTS goodie_frames(
            frame_key TEXT PRIMARY KEY,build_id INTEGER NOT NULL REFERENCES structure_builds(id) ON DELETE CASCADE,
            slot INTEGER NOT NULL CHECK(slot BETWEEN -1 AND 7),world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,
            facing TEXT NOT NULL CHECK(facing IN ('NORTH','SOUTH','EAST','WEST')),is_source INTEGER NOT NULL CHECK(is_source IN (0,1)),
            entity_uuid TEXT UNIQUE,UNIQUE(build_id,slot),UNIQUE(world,x,y,z),CHECK((slot=-1 AND is_source=1) OR (slot>=0 AND is_source=0)))
            """);}
    }
    public static Frame register(Connection db,long build,String world,int x,int y,int z,GoodieFrameMarker marker)throws SQLException {
        String key=build+":"+marker.slot();
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO goodie_frames VALUES(?,?,?,?,?,?,?,?,?,NULL)")) {
            s.setString(1,key);s.setLong(2,build);s.setInt(3,marker.slot());s.setString(4,world);s.setInt(5,x+marker.x());s.setInt(6,y+marker.y());s.setInt(7,z+marker.z());s.setString(8,marker.facing());s.setBoolean(9,marker.source());
            s.executeUpdate();
        }
        var frame=find(db,key);
        if(frame==null || !frame.world().equals(world)||frame.x()!=x+marker.x()||frame.y()!=y+marker.y()||frame.z()!=z+marker.z()||!frame.facing().equals(marker.facing()))
            throw new IllegalArgumentException("Позиция рамки торгового товара уже занята или изменилась.");
        return frame;
    }
    public static Frame find(Connection db,String key)throws SQLException {
        try(var s=db.prepareStatement("SELECT * FROM goodie_frames WHERE frame_key=?")){s.setString(1,key);try(var r=s.executeQuery()){return r.next()?read(r):null;}}
    }
    public static List<Frame> building(Connection db,long build)throws SQLException {
        var result=new ArrayList<Frame>();try(var s=db.prepareStatement("SELECT * FROM goodie_frames WHERE build_id=? ORDER BY slot")){s.setLong(1,build);try(var r=s.executeQuery()){while(r.next())result.add(read(r));}}return List.copyOf(result);
    }
    public static void entity(Connection db,String key,UUID entity)throws SQLException {
        try(var s=db.prepareStatement("UPDATE goodie_frames SET entity_uuid=? WHERE frame_key=?")){s.setString(1,entity.toString());s.setString(2,key);if(s.executeUpdate()!=1)throw new SQLException("Goodie frame missing");}
    }
    private static Frame read(ResultSet r)throws SQLException {
        return new Frame(r.getString("frame_key"),r.getLong("build_id"),r.getInt("slot"),r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getString("facing"),r.getBoolean("is_source"),r.getString("entity_uuid"));
    }
}
