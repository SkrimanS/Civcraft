package com.avrgaming.civcraft.modern.wall;

import java.sql.*;
import java.util.UUID;

/** Original isProtectedLocation: active wall, matching X/Z, any Y below its configured ceiling. */
public final class WallColumnStore {
    private WallColumnStore() {}
    public record Protection(long build,long civilization) {}
    public static Protection protecting(Connection db,String world,int x,int y,int z)throws SQLException {
        if(!WallStore.installed(db))return null;
        try(var s=db.prepareStatement("""
            SELECT w.build_id,t.civ_id FROM wall_columns c JOIN walls w ON w.build_id=c.build_id
            JOIN active_structure_builds b ON b.id=w.build_id JOIN towns t ON t.id=b.town_id
            LEFT JOIN building_combat_health h ON h.build_id=b.id
            WHERE b.world=? AND c.x=? AND c.z=? AND ?<w.maximum_height AND w.state='ACTIVE'
              AND b.status='COMPLETE' AND COALESCE(h.hitpoints,b.max_hitpoints)>0 ORDER BY w.build_id LIMIT 1
            """)) {
            s.setString(1,world);s.setInt(2,x);s.setInt(3,z);s.setInt(4,y);try(var r=s.executeQuery()){return r.next()?new Protection(r.getLong(1),r.getLong(2)):null;}
        }
    }
    public static boolean registered(Connection db,String world,int x,int y,int z)throws SQLException {
        if(!WallStore.installed(db))return false;
        try(var s=db.prepareStatement("SELECT 1 FROM protected_blocks p JOIN walls w ON w.build_id=p.build_id WHERE p.world=? AND p.x=? AND p.y=? AND p.z=? AND w.state<>'RETIRED' LIMIT 1")) {
            s.setString(1,world);s.setInt(2,x);s.setInt(3,y);s.setInt(4,z);try(var r=s.executeQuery()){return r.next();}
        }
    }
    public static boolean sameCivilization(Connection db,UUID player,long civilization)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM residents r JOIN towns t ON t.id=r.town_id WHERE r.uuid=? AND r.civ_id=t.civ_id AND t.civ_id=?")) {
            s.setString(1,player.toString());s.setLong(2,civilization);try(var r=s.executeQuery()){return r.next();}
        }
    }
}
