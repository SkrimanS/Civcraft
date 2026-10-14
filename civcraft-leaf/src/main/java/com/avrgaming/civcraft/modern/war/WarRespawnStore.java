package com.avrgaming.civcraft.modern.war;

import java.sql.*;
import java.util.*;

public final class WarRespawnStore {
    private WarRespawnStore() {}
    public record Point(long build,String kind,String world,int x,int y,int z) {}
    public record SignPoint(long build,String action,String world,int x,int y,int z) {}
    public record Destination(Long build,long town,String name,String world,int x,int y,int z,Long camp) {
        public Destination(long build,long town,String name,String world,int x,int y,int z){this(build,town,name,world,x,y,z,null);}
    }
    public static void install(Connection db)throws SQLException {
        WarCampStore.install(db);
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS war_respawn_points(build_id INTEGER NOT NULL REFERENCES structure_builds(id) ON DELETE CASCADE,kind TEXT NOT NULL CHECK(kind IN ('respawn','revive')),world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,PRIMARY KEY(build_id,kind,world,x,y,z))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS war_resident_respawns(uuid TEXT PRIMARY KEY REFERENCES residents(uuid) ON DELETE CASCADE,last_killed_at INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS war_respawn_signs(build_id INTEGER NOT NULL REFERENCES structure_builds(id) ON DELETE CASCADE,action TEXT NOT NULL CHECK(action IN ('respawndata','next','prev')),world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,PRIMARY KEY(world,x,y,z))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS war_respawn_selection(build_id INTEGER PRIMARY KEY REFERENCES structure_builds(id) ON DELETE CASCADE,selected_index INTEGER NOT NULL DEFAULT 0 CHECK(selected_index>=0))");
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
    public static void registerSign(Connection db,SignPoint point)throws SQLException {
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO war_respawn_signs VALUES(?,?,?,?,?,?)")){s.setLong(1,point.build());s.setString(2,point.action());s.setString(3,point.world());s.setInt(4,point.x());s.setInt(5,point.y());s.setInt(6,point.z());s.executeUpdate();}
    }
    public static SignPoint sign(Connection db,String world,int x,int y,int z)throws SQLException {
        try(var s=db.prepareStatement("SELECT p.* FROM war_respawn_signs p JOIN structure_builds b ON b.id=p.build_id WHERE p.world=? AND p.x=? AND p.y=? AND p.z=? AND b.status='COMPLETE' AND b.structure_id='s_capitol'")){
            s.setString(1,world);s.setInt(2,x);s.setInt(3,y);s.setInt(4,z);try(var r=s.executeQuery()){return r.next()?new SignPoint(r.getLong("build_id"),r.getString("action"),world,x,y,z):null;}
        }
    }
    public static long owner(Connection db,long build)throws SQLException {
        try(var s=db.prepareStatement("SELECT t.civ_id FROM structure_builds b JOIN towns t ON t.id=b.town_id WHERE b.id=? AND b.status='COMPLETE' AND b.structure_id='s_capitol'")){s.setLong(1,build);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Капитолий недоступен.");return r.getLong(1);}}
    }
    public static void authorize(Connection db,long build,UUID actor,boolean selection,long now)throws SQLException {
        if(!WarClockStore.active(db,now))throw new IllegalArgumentException("Выход через военную комнату доступен только в боевой период.");
        long civ=owner(db,build);
        try(var s=db.prepareStatement("SELECT t.mayor_uuid,c.leader_uuid,r.uuid,EXISTS(SELECT 1 FROM civ_advisors a WHERE a.civ_id=c.id AND a.resident_uuid=r.uuid) FROM structure_builds b JOIN towns t ON t.id=b.town_id JOIN civilizations c ON c.id=t.civ_id JOIN residents r ON r.civ_id=c.id JOIN towns own ON own.id=r.town_id AND own.civ_id=c.id WHERE b.id=? AND c.id=? AND r.uuid=?")) {
            s.setLong(1,build);s.setLong(2,civ);s.setString(3,actor.toString());try(var r=s.executeQuery()) {
                if(!r.next())throw new IllegalArgumentException("Это военная комната другой цивилизации.");
                if(selection&&!r.getString(3).equals(r.getString(1))&&!r.getString(3).equals(r.getString(2))&&!r.getBoolean(4))throw new IllegalArgumentException("Место выхода выбирает мэр, лидер или советник.");
            }
        }
    }
    public static List<Destination> destinations(Connection db,long build,long now)throws SQLException {
        long civ=owner(db,build);var out=new ArrayList<Destination>();
        try(var s=db.prepareStatement("SELECT b.id,b.town_id,t.name,b.world,b.x,b.y,b.z FROM structure_builds b JOIN towns t ON t.id=b.town_id JOIN civilizations c ON c.id=t.civ_id WHERE c.id=? AND b.status='COMPLETE' AND b.structure_id IN ('s_capitol','s_townhall') AND (c.capital_town_id=t.id OR NOT EXISTS(SELECT 1 FROM war_inactive_towns d JOIN war_sessions w ON w.start_at=d.war_start WHERE d.town_id=t.id AND w.state='ACTIVE' AND w.start_at<=? AND w.end_at>?)) ORDER BY t.id,b.id")){
            s.setLong(1,civ);s.setLong(2,now);s.setLong(3,now);try(var r=s.executeQuery()){while(r.next())out.add(new Destination(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getInt(5),r.getInt(6),r.getInt(7)));}
        }
        try(var s=db.prepareStatement("SELECT c.* FROM war_camps c JOIN war_sessions w ON w.start_at=c.war_start WHERE c.civ_id=? AND c.state='ACTIVE' AND w.state='ACTIVE' AND w.start_at<=? AND w.end_at>? ORDER BY c.id")) {
            s.setLong(1,civ);s.setLong(2,now);s.setLong(3,now);try(var r=s.executeQuery()){while(r.next())out.add(new Destination(null,r.getLong("town_id"),"Военный лагерь ("+r.getInt("x")+","+r.getInt("y")+","+r.getInt("z")+")",r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getLong("id")));}
        }
        return List.copyOf(out);
    }
    public static int selected(Connection db,long build)throws SQLException {
        try(var s=db.prepareStatement("SELECT selected_index FROM war_respawn_selection WHERE build_id=?")){s.setLong(1,build);try(var r=s.executeQuery()){return r.next()?r.getInt(1):0;}}
    }
    public static void select(Connection db,long build,int index)throws SQLException {
        try(var s=db.prepareStatement("INSERT INTO war_respawn_selection VALUES(?,?) ON CONFLICT(build_id) DO UPDATE SET selected_index=excluded.selected_index")){s.setLong(1,build);s.setInt(2,index);s.executeUpdate();}
    }
    public static int cycle(Connection db,long build,UUID actor,int step,long now)throws SQLException {
        authorize(db,build,actor,true,now);int size=destinations(db,build,now).size(),next=selected(db,build)+step;
        // The original changeIndex resets both underflow and overflow to zero.
        next=next<0||next>=size?0:next;select(db,build,next);return next;
    }
    public static int destroyedControls(Connection db,long build,long now)throws SQLException {
        try(var s=db.prepareStatement("SELECT COUNT(*) FROM war_control_points p JOIN war_control_health h ON h.world=p.world AND h.x=p.x AND h.y=p.y AND h.z=p.z JOIN war_sessions w ON w.start_at=h.war_start WHERE p.build_id=? AND h.hitpoints=0 AND w.state='ACTIVE' AND w.start_at<=? AND w.end_at>?")){s.setLong(1,build);s.setLong(2,now);s.setLong(3,now);try(var r=s.executeQuery()){r.next();return r.getInt(1);}}
    }
    public static int delaySeconds(int base,int perControl,int destroyed,boolean invalid,int invalidMinutes,int medicine) {
        int seconds=Math.addExact(base,Math.multiplyExact(perControl,destroyed));if(invalid)seconds=Math.addExact(seconds,Math.multiplyExact(invalidMinutes,60));
        return medicine>0?Math.max(1,seconds-medicine):seconds;
    }
    public static long remainingMillis(Long killedAt,int seconds,long now) {
        return killedAt==null?0:Math.max(0,Math.addExact(killedAt,Math.multiplyExact((long)seconds,1000))-now);
    }
    public static List<Point> revivePoints(Connection db,long build)throws SQLException {
        var out=new ArrayList<Point>();try(var s=db.prepareStatement("SELECT * FROM war_respawn_points WHERE build_id=? AND kind='revive' ORDER BY world,x,y,z")){s.setLong(1,build);try(var r=s.executeQuery()){while(r.next())out.add(new Point(build,"revive",r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z")));}}return out;
    }
    public static List<Point> destinationPoints(Connection db,long capitol,Destination destination,long now)throws SQLException {
        if(destination.camp()==null)return revivePoints(db,destination.build());
        long civ=owner(db,capitol);var camp=WarCampStore.camp(db,destination.camp());
        if(camp.civilization()!=civ||!camp.state().equals("ACTIVE"))throw new IllegalArgumentException("Военный лагерь больше недоступен для выхода.");
        try(var s=db.prepareStatement("SELECT 1 FROM war_sessions WHERE start_at=? AND state='ACTIVE' AND start_at<=? AND end_at>?")){s.setLong(1,camp.war());s.setLong(2,now);s.setLong(3,now);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Боевой период военного лагеря завершён.");}}
        var out=new ArrayList<Point>();try(var s=db.prepareStatement("SELECT * FROM war_camp_points WHERE camp_id=? AND kind='respawn' ORDER BY world,x,y,z")){s.setLong(1,camp.id());try(var r=s.executeQuery()){while(r.next())out.add(new Point(0,"revive",r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z")));}}return List.copyOf(out);
    }
}
