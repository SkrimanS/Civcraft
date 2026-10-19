package com.avrgaming.civcraft.modern.war;

import java.sql.*;
import java.util.*;

/** A hall shell is separate from capture: a lethal shell hit reduces one control block to 1 HP. */
public final class SiegeCannonImpactStore {
    private SiegeCannonImpactStore() {}
    public record Target(long build,String type,String status,boolean camp,boolean damageable) {}
    public static void install(Connection db)throws SQLException{
        try(var s=db.createStatement()){
            s.executeUpdate("CREATE TABLE IF NOT EXISTS siege_hall_hits(id TEXT PRIMARY KEY,build_id INTEGER NOT NULL,war_start INTEGER NOT NULL,after_hp INTEGER NOT NULL,control_x INTEGER,control_y INTEGER,control_z INTEGER)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS siege_player_hits(id TEXT NOT NULL,player_uuid TEXT NOT NULL,damage REAL NOT NULL,state TEXT NOT NULL DEFAULT 'PREPARED',PRIMARY KEY(id,player_uuid))");
        }
    }
    public static Target target(Connection db,String world,int x,int y,int z,String campType)throws SQLException{
        try(var s=db.prepareStatement("SELECT b.id,b.structure_id,b.status,b.y,(EXISTS(SELECT 1 FROM structure_trade_points t WHERE t.world=p.world AND t.x=p.x AND t.y=p.y AND t.z=p.z) OR EXISTS(SELECT 1 FROM structure_chests c WHERE c.world=p.world AND c.x=p.x AND c.y=p.y AND c.z=p.z)) FROM protected_blocks p JOIN structure_builds b ON b.id=p.build_id WHERE p.world=? AND p.x=? AND p.y=? AND p.z=?")){
            s.setString(1,world);s.setInt(2,x);s.setInt(3,y);s.setInt(4,z);try(var r=s.executeQuery()){if(!r.next())return null;String type=r.getString(2);return new Target(r.getLong(1),type,r.getString(3),campType.equals(type),y>r.getInt(4) && !r.getBoolean(5));}
        }
    }
    public static boolean camp(Connection db,String world,int x,int y,int z)throws SQLException{
        try(var s=db.prepareStatement("SELECT 1 FROM war_camp_blocks b JOIN war_camps c ON c.id=b.camp_id WHERE b.world=? AND b.x=? AND b.y=? AND b.z=? AND c.state<>'RESTORED'")){
            s.setString(1,world);s.setInt(2,x);s.setInt(3,y);s.setInt(4,z);try(var r=s.executeQuery()){return r.next();}
        }
    }
    public static int hall(Connection db,String receipt,long build,int damage,int controlFloor,long war)throws SQLException{
        if(db.getAutoCommit())throw new SQLException("Hall shell damage requires a transaction");
        try(var s=db.prepareStatement("SELECT after_hp FROM siege_hall_hits WHERE id=?")){s.setString(1,receipt);try(var r=s.executeQuery()){if(r.next())return r.getInt(1);}}
        try(var s=db.prepareStatement("SELECT t.civ_id FROM structure_builds b JOIN towns t ON t.id=b.town_id WHERE b.id=? AND b.status='COMPLETE'")){
            s.setLong(1,build);try(var r=s.executeQuery()){if(!r.next() || !WarClockStore.hasWars(db,r.getLong(1)))return -1;}
        }
        var before=BuildingDamageStore.health(db,build);if(before.hitpoints()==0 || before.maximum()<1)return 0;
        int hp=Math.max(0,before.hitpoints()-damage);ControlPointStore.Point reduced=null;
        if(hp==0){
            var points=new ArrayList<ControlPointStore.Point>();try(var s=db.prepareStatement("SELECT world,x,y,z FROM war_control_points WHERE build_id=? ORDER BY x,y,z")){s.setLong(1,build);try(var r=s.executeQuery()){while(r.next())points.add(ControlPointStore.point(db,r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4)));}}
            for(var point:points)if(ControlPointStore.health(db,point,war)>controlFloor){
                reduced=point;hp=before.maximum()/2;
                try(var s=db.prepareStatement("INSERT INTO war_control_health VALUES(?,?,?,?,?,1) ON CONFLICT(war_start,world,x,y,z) DO UPDATE SET hitpoints=1")){s.setLong(1,war);s.setString(2,point.world());s.setInt(3,point.x());s.setInt(4,point.y());s.setInt(5,point.z());s.executeUpdate();}break;
            }
        }
        try(var s=db.prepareStatement("INSERT INTO building_combat_health VALUES(?,?,?,'DAMAGED') ON CONFLICT(build_id) DO UPDATE SET hitpoints=excluded.hitpoints,maximum=excluded.maximum,phase='DAMAGED'")){s.setLong(1,build);s.setInt(2,hp);s.setInt(3,before.maximum());s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO siege_hall_hits VALUES(?,?,?,?,?,?,?)")){s.setString(1,receipt);s.setLong(2,build);s.setLong(3,war);s.setInt(4,hp);if(reduced==null){s.setNull(5,Types.INTEGER);s.setNull(6,Types.INTEGER);s.setNull(7,Types.INTEGER);}else{s.setInt(5,reduced.x());s.setInt(6,reduced.y());s.setInt(7,reduced.z());}s.executeUpdate();}return hp;
    }
}
