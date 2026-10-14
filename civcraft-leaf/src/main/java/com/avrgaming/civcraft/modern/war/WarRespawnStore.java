package com.avrgaming.civcraft.modern.war;

import java.sql.*;
import java.util.*;

public final class WarRespawnStore {
    private WarRespawnStore() {}
    public record Point(long build,String kind,String world,int x,int y,int z) {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS war_respawn_points(build_id INTEGER NOT NULL REFERENCES structure_builds(id) ON DELETE CASCADE,kind TEXT NOT NULL CHECK(kind IN ('respawn','revive')),world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,PRIMARY KEY(build_id,kind,world,x,y,z))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS war_resident_respawns(uuid TEXT PRIMARY KEY REFERENCES residents(uuid) ON DELETE CASCADE,last_killed_at INTEGER NOT NULL)");
        }
    }
    public static void register(Connection db,Point point)throws SQLException {
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO war_respawn_points VALUES(?,?,?,?,?,?)")) {
            s.setLong(1,point.build());s.setString(2,point.kind());s.setString(3,point.world());s.setInt(4,point.x());s.setInt(5,point.y());s.setInt(6,point.z());s.executeUpdate();
        }
    }
    public static List<Point> room(Connection db,UUID resident,long now)throws SQLException {
        if(!WarClockStore.active(db,now))return List.of();
        Long civ=null;
        try(var s=db.prepareStatement("SELECT t.civ_id FROM residents r JOIN towns t ON t.id=r.town_id AND t.civ_id=r.civ_id WHERE r.uuid=?")) {
            s.setString(1,resident.toString());try(var r=s.executeQuery()){if(r.next())civ=r.getLong(1);}
        }
        if(civ==null || !WarClockStore.hasWars(db,civ))return List.of();
        var points=new ArrayList<Point>();
        try(var s=db.prepareStatement("SELECT p.* FROM war_respawn_points p JOIN structure_builds b ON b.id=p.build_id JOIN civilizations c ON c.capital_town_id=b.town_id JOIN towns t ON t.id=b.town_id AND t.civ_id=c.id WHERE c.id=? AND b.structure_id='s_capitol' AND b.status='COMPLETE' AND p.kind='respawn' ORDER BY p.world,p.x,p.y,p.z")) {
            s.setLong(1,civ);try(var r=s.executeQuery()){while(r.next())points.add(new Point(r.getLong("build_id"),r.getString("kind"),r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z")));}
        }return List.copyOf(points);
    }
    public static void recordArrival(Connection db,UUID resident,long now)throws SQLException {
        try(var s=db.prepareStatement("INSERT INTO war_resident_respawns(uuid,last_killed_at) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET last_killed_at=excluded.last_killed_at")){s.setString(1,resident.toString());s.setLong(2,now);s.executeUpdate();}
    }
    public static Long lastKilled(Connection db,UUID resident)throws SQLException {
        try(var s=db.prepareStatement("SELECT last_killed_at FROM war_resident_respawns WHERE uuid=?")){s.setString(1,resident.toString());try(var r=s.executeQuery()){return r.next()?r.getLong(1):null;}}
    }
}
