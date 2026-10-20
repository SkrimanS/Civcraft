package com.avrgaming.civcraft.modern.war;

import java.sql.*;
import java.util.*;
import com.avrgaming.civcraft.modern.build.BuildQueue;

/** The received event, HP changes and world intent have independent durable stages. */
public final class LegacyExplosionStore {
    private LegacyExplosionStore() {}
    public record Request(String id,long war,String world,int x,int y,int z,int radius,int damage) {}
    public record Hit(long build,int before,int after,boolean stoppedConstruction) {}
    private static void transaction(Connection db)throws SQLException {if(db.getAutoCommit())throw new SQLException("Legacy explosion requires a transaction");}
    public static boolean installed(Connection db)throws SQLException {
        try(var s=db.createStatement();var r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='legacy_explosion_requests'")){return r.next();}
    }
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS legacy_explosion_requests(id TEXT PRIMARY KEY,war_start INTEGER NOT NULL,world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,radius INTEGER NOT NULL,damage INTEGER NOT NULL,state TEXT NOT NULL CHECK(state IN ('RECEIVED','PREPARED','DONE','CANCELLED')))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS legacy_explosion_hits(explosion_id TEXT NOT NULL REFERENCES legacy_explosion_requests(id),build_id INTEGER NOT NULL,war_start INTEGER NOT NULL,before_hp INTEGER NOT NULL,after_hp INTEGER NOT NULL,stopped_construction INTEGER NOT NULL,PRIMARY KEY(explosion_id,build_id))");
        }
        boolean damageReady=false;try(var s=db.createStatement();var r=s.executeQuery("PRAGMA table_info(legacy_explosion_requests)")){while(r.next())if(r.getString("name").equals("damage_ready"))damageReady=true;}
        if(!damageReady)try(var s=db.createStatement()){s.executeUpdate("ALTER TABLE legacy_explosion_requests ADD COLUMN damage_ready INTEGER NOT NULL DEFAULT 0");}
    }
    public static Long activeWar(Connection db,long now)throws SQLException {
        try(var s=db.prepareStatement("SELECT start_at FROM war_sessions WHERE state='ACTIVE' AND start_at<=? AND end_at>? ORDER BY start_at LIMIT 1")){s.setLong(1,now);s.setLong(2,now);try(var r=s.executeQuery()){return r.next()?r.getLong(1):null;}}
    }
    public static void receive(Connection db,Request r)throws SQLException {
        transaction(db);try(var s=db.prepareStatement("INSERT OR IGNORE INTO legacy_explosion_requests(id,war_start,world,x,y,z,radius,damage,state) VALUES(?,?,?,?,?,?,?,?, 'RECEIVED')")){s.setString(1,r.id());s.setLong(2,r.war());s.setString(3,r.world());s.setInt(4,r.x());s.setInt(5,r.y());s.setInt(6,r.z());s.setInt(7,r.radius());s.setInt(8,r.damage());s.executeUpdate();}
    }
    public static boolean damageReady(Connection db,String id)throws SQLException {
        try(var s=db.prepareStatement("SELECT damage_ready FROM legacy_explosion_requests WHERE id=?")){s.setString(1,id);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing explosion request");return r.getBoolean(1);}}
    }
    public static void damagePrepared(Connection db,String id)throws SQLException {
        transaction(db);try(var s=db.prepareStatement("UPDATE legacy_explosion_requests SET damage_ready=1 WHERE id=? AND state='RECEIVED' AND damage_ready=0")){s.setString(1,id);if(s.executeUpdate()!=1)throw new SQLException("Explosion damage stage changed");}
    }
    public static List<Request> pending(Connection db)throws SQLException {
        var out=new ArrayList<Request>();try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM legacy_explosion_requests WHERE state='RECEIVED' ORDER BY rowid")){while(r.next())out.add(new Request(r.getString("id"),r.getLong("war_start"),r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getInt("radius"),r.getInt("damage")));}return out;
    }
    public static void prepared(Connection db,String id)throws SQLException {
        transaction(db);try(var s=db.prepareStatement("UPDATE legacy_explosion_requests SET state='PREPARED' WHERE id=? AND state='RECEIVED'")){s.setString(1,id);if(s.executeUpdate()!=1)throw new SQLException("Explosion request changed during preparation");}
    }
    public static void worldDone(Connection db,String id)throws SQLException {
        if(!installed(db))return;transaction(db);try(var s=db.prepareStatement("UPDATE legacy_explosion_requests SET state='DONE' WHERE id=? AND state='PREPARED'")){s.setString(1,id);s.executeUpdate();}
    }
    public static void expire(Connection db,long now)throws SQLException {
        transaction(db);try(var s=db.prepareStatement("UPDATE legacy_explosion_requests SET state='CANCELLED' WHERE state IN ('RECEIVED','PREPARED') AND NOT EXISTS(SELECT 1 FROM war_sessions w WHERE w.start_at=war_start AND w.state='ACTIVE' AND w.start_at<=? AND w.end_at>?)")){s.setLong(1,now);s.setLong(2,now);s.executeUpdate();}
    }
    public static Hit damage(Connection db,Request request,SiegeCannonImpactStore.Target target,BuildQueue.DestructionSnapshot construction)throws SQLException {
        transaction(db);
        try(var s=db.prepareStatement("SELECT before_hp,after_hp,stopped_construction FROM legacy_explosion_hits WHERE explosion_id=? AND build_id=?")){s.setString(1,request.id());s.setLong(2,target.build());try(var r=s.executeQuery()){if(r.next())return new Hit(target.build(),r.getInt(1),r.getInt(2),r.getBoolean(3));}}
        var before=BuildingDamageStore.health(db,target.build());int after=before.hitpoints();boolean stopped=false;
        Long civ=null;try(var s=db.prepareStatement("SELECT t.civ_id FROM structure_builds b JOIN towns t ON t.id=b.town_id WHERE b.id=?")){s.setLong(1,target.build());try(var r=s.executeQuery()){if(r.next())civ=r.getLong(1);}}
        boolean hall=Set.of("s_capitol","s_townhall").contains(target.type());
        boolean complete=target.status().equals("COMPLETE"),wonder=target.type().startsWith("w_");
        boolean building=Set.of("QUEUED","BUILDING").contains(target.status());
        if(civ!=null && WarClockStore.hasWars(db,civ) && before.hitpoints()>0 && request.damage()>0 && (hall || complete || wonder && building)) {
            if(hall){if((long)before.hitpoints()>=(long)request.damage()+1)after-=request.damage();}
            else after=Math.max(0,before.hitpoints()-request.damage());
            if(after!=before.hitpoints()) {
                if(after==0 && wonder && building) {
                    if(construction==null)throw new SQLException("Missing unfinished wonder destruction snapshot");
                    BuildingRuinStore.sabotageWonder(db,target.build(),construction);stopped=true;
                }else {
                    try(var s=db.prepareStatement("INSERT INTO building_combat_health VALUES(?,?,?,?) ON CONFLICT(build_id) DO UPDATE SET hitpoints=excluded.hitpoints,maximum=excluded.maximum,phase=excluded.phase")){s.setLong(1,target.build());s.setInt(2,after);s.setInt(3,before.maximum());s.setString(4,after==0?"DESTROY_PENDING":"DAMAGED");s.executeUpdate();}
                    if(after==0) {
                        if(wonder)BuildingRuinStore.destroyCompletedWonder(db,target.build());
                        else try(var s=db.prepareStatement("UPDATE structure_builds SET status='WAR_DESTROY_PENDING' WHERE id=? AND status='COMPLETE'")){s.setLong(1,target.build());if(s.executeUpdate()!=1)throw new SQLException("Explosion destruction target changed");}
                    }
                }
            }
        }
        try(var s=db.prepareStatement("INSERT INTO legacy_explosion_hits VALUES(?,?,?,?,?,?)")){s.setString(1,request.id());s.setLong(2,target.build());s.setLong(3,request.war());s.setInt(4,before.hitpoints());s.setInt(5,after);s.setBoolean(6,stopped);s.executeUpdate();}
        return new Hit(target.build(),before.hitpoints(),after,stopped);
    }
}
