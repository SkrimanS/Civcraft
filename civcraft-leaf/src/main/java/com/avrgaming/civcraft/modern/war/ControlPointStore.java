package com.avrgaming.civcraft.modern.war;
import com.avrgaming.civcraft.modern.diplomacy.DiplomacyStore;
import java.sql.*;
import java.util.*;
public final class ControlPointStore {
    private ControlPointStore() {}
    public record Point(long build,String world,int x,int y,int z,int maximum,String base) {}
    public record Hit(long war,Point point,int health,boolean townDefeated,boolean civDefeated) {}
    public static void install(Connection db)throws SQLException {
        ConquestStore.install(db);
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS war_control_points(build_id INTEGER NOT NULL REFERENCES structure_builds(id) ON DELETE CASCADE,world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,maximum INTEGER NOT NULL CHECK(maximum>0),base TEXT NOT NULL,PRIMARY KEY(world,x,y,z))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS war_control_health(war_start INTEGER NOT NULL REFERENCES war_sessions(start_at),world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,hitpoints INTEGER NOT NULL CHECK(hitpoints>=0),PRIMARY KEY(war_start,world,x,y,z),FOREIGN KEY(world,x,y,z) REFERENCES war_control_points(world,x,y,z) ON DELETE CASCADE)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS war_control_hits(id TEXT PRIMARY KEY,war_start INTEGER NOT NULL,world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,actor TEXT NOT NULL,before_hp INTEGER NOT NULL,after_hp INTEGER NOT NULL,at INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS war_defeated_civs(war_start INTEGER NOT NULL REFERENCES war_sessions(start_at),civ_id INTEGER NOT NULL REFERENCES civilizations(id),conqueror INTEGER NOT NULL REFERENCES civilizations(id),PRIMARY KEY(war_start,civ_id))");
        }
    }
    public static void register(Connection db,Point p)throws SQLException {
        try(var s=db.prepareStatement("SELECT structure_id,status FROM structure_builds WHERE id=?")) {
            s.setLong(1,p.build());try(var r=s.executeQuery()){if(!r.next() || !r.getString(2).equals("COMPLETE") || !Set.of("s_capitol","s_townhall").contains(r.getString(1)))throw new SQLException("Control point requires a complete town hall or capitol");}
        }
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO war_control_points VALUES(?,?,?,?,?,?,?)")) {
            s.setLong(1,p.build());s.setString(2,p.world());s.setInt(3,p.x());s.setInt(4,p.y());s.setInt(5,p.z());s.setInt(6,p.maximum());s.setString(7,p.base());s.executeUpdate();
        }
        var registered=point(db,p.world(),p.x(),p.y(),p.z());if(!registered.equals(p))throw new SQLException("Control point definition conflict");
        for(int dy:new int[]{-1,0})try(var s=db.prepareStatement("INSERT INTO protected_blocks(world,x,y,z,build_id,material) VALUES(?,?,?,?,?,?) ON CONFLICT(world,x,y,z) DO UPDATE SET material=excluded.material WHERE protected_blocks.build_id=excluded.build_id")) {
            s.setString(1,p.world());s.setInt(2,p.x());s.setInt(3,p.y()+dy);s.setInt(4,p.z());s.setLong(5,p.build());s.setString(6,dy==0?"OBSIDIAN":p.base());s.executeUpdate();
            try(var check=db.prepareStatement("SELECT build_id FROM protected_blocks WHERE world=? AND x=? AND y=? AND z=?")){check.setString(1,p.world());check.setInt(2,p.x());check.setInt(3,p.y()+dy);check.setInt(4,p.z());try(var r=check.executeQuery()){if(!r.next() || r.getLong(1)!=p.build())throw new SQLException("Control point overlaps another building");}}
        }
    }
    public static Point point(Connection db,String world,int x,int y,int z)throws SQLException {
        try(var s=db.prepareStatement("SELECT * FROM war_control_points WHERE world=? AND x=? AND y=? AND z=?")) {
            s.setString(1,world);s.setInt(2,x);s.setInt(3,y);s.setInt(4,z);try(var r=s.executeQuery()){return r.next()?new Point(r.getLong("build_id"),world,x,y,z,r.getInt("maximum"),r.getString("base")):null;}
        }
    }
    public static int health(Connection db,Point point,long war)throws SQLException {
        try(var s=db.prepareStatement("SELECT hitpoints FROM war_control_health WHERE war_start=? AND world=? AND x=? AND y=? AND z=?")){s.setLong(1,war);s.setString(2,point.world());s.setInt(3,point.x());s.setInt(4,point.y());s.setInt(5,point.z());try(var r=s.executeQuery()){return r.next()?r.getInt(1):point.maximum();}}
    }
    public static Hit hit(Connection db,String id,UUID actor,Point point,int damage,long now)throws SQLException {
        if(id==null || id.isBlank() || damage<1)throw new IllegalArgumentException("Invalid control damage");
        try(var s=db.prepareStatement("SELECT war_start,world,x,y,z FROM war_control_hits WHERE id=?")){s.setString(1,id);try(var r=s.executeQuery()){if(r.next()){var actual=point(db,r.getString(2),r.getInt(3),r.getInt(4),r.getInt(5));long war=r.getLong(1);return new Hit(war,actual,health(db,actual,war),false,false);}}}
        if(!point.equals(point(db,point.world(),point.x(),point.y(),point.z())))throw new IllegalArgumentException("Unknown control point");
        CapitalStore.seed(db);
        long war;
        try(var s=db.prepareStatement("SELECT start_at FROM war_sessions WHERE state='ACTIVE' AND start_at<=? AND end_at>? ORDER BY start_at LIMIT 1")){s.setLong(1,now);s.setLong(2,now);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Контрольные блоки атакуют только во время боя.");war=r.getLong(1);}}
        long town,civ;
        try(var s=db.prepareStatement("SELECT t.id,t.civ_id FROM residents r JOIN towns t ON t.id=r.town_id AND t.civ_id=r.civ_id WHERE r.uuid=?")){s.setString(1,actor.toString());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Для осады нужно состоять в городе.");town=r.getLong(1);civ=r.getLong(2);}}
        try(var s=db.prepareStatement("SELECT 1 FROM war_defeated_towns WHERE war_start=? AND town_id=? UNION ALL SELECT 1 FROM war_inactive_towns WHERE war_start=? AND town_id=?")){s.setLong(1,war);s.setLong(2,town);s.setLong(3,war);s.setLong(4,town);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Ваш город уже побеждён в этой войне.");}}
        long defenderTown,defender;boolean capitol;
        try(var s=db.prepareStatement("SELECT b.town_id,t.civ_id,b.status,c.capital_town_id FROM structure_builds b JOIN towns t ON t.id=b.town_id JOIN civilizations c ON c.id=t.civ_id WHERE b.id=?")){s.setLong(1,point.build());try(var r=s.executeQuery()){if(!r.next() || !r.getString(3).equals("COMPLETE"))throw new IllegalArgumentException("Ратуша неактивна.");defenderTown=r.getLong(1);defender=r.getLong(2);capitol=r.getObject(4)!=null && defenderTown==r.getLong(4);}}
        if(DiplomacyStore.status(db,civ,defender)!=DiplomacyStore.Status.WAR)throw new IllegalArgumentException("Ваша цивилизация не воюет с этим городом.");
        try(var s=db.prepareStatement("SELECT 1 FROM war_defeated_towns WHERE war_start=? AND town_id=?")){s.setLong(1,war);s.setLong(2,defenderTown);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Этот город уже побеждён.");}}
        int before=health(db,point,war);if(before==0)throw new IllegalArgumentException("Контрольный блок уже разрушен.");int after=Math.max(0,before-damage);
        try(var s=db.prepareStatement("INSERT INTO war_control_health VALUES(?,?,?,?,?,?) ON CONFLICT(war_start,world,x,y,z) DO UPDATE SET hitpoints=excluded.hitpoints")){s.setLong(1,war);s.setString(2,point.world());s.setInt(3,point.x());s.setInt(4,point.y());s.setInt(5,point.z());s.setInt(6,after);s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO war_control_hits VALUES(?,?,?,?,?,?,?,?,?,?)")){s.setString(1,id);s.setLong(2,war);s.setString(3,point.world());s.setInt(4,point.x());s.setInt(5,point.y());s.setInt(6,point.z());s.setString(7,actor.toString());s.setInt(8,before);s.setInt(9,after);s.setLong(10,now);s.executeUpdate();}
        boolean defeated=false;
        if(after==0)try(var s=db.prepareStatement("SELECT COUNT(*) FROM war_control_points p LEFT JOIN war_control_health h ON h.war_start=? AND h.world=p.world AND h.x=p.x AND h.y=p.y AND h.z=p.z WHERE p.build_id=? AND COALESCE(h.hitpoints,p.maximum)>0")){s.setLong(1,war);s.setLong(2,point.build());try(var r=s.executeQuery()){r.next();defeated=r.getInt(1)==0;}}
        if(defeated) {
            // Battle eligibility survives changes to who receives the captured town.
            try(var s=db.prepareStatement(capitol?"INSERT OR IGNORE INTO war_inactive_towns SELECT ?,id FROM towns WHERE civ_id=?":"INSERT OR IGNORE INTO war_inactive_towns VALUES(?,?)")){s.setLong(1,war);s.setLong(2,capitol?defender:defenderTown);s.executeUpdate();}
            if(capitol) {
                // The new victor inherits victories previously earned by the defeated civ.
                try(var s=db.prepareStatement("DELETE FROM war_defeated_towns WHERE war_start=? AND conqueror=? AND town_id IN (SELECT id FROM towns WHERE civ_id=?)")){s.setLong(1,war);s.setLong(2,defender);s.setLong(3,civ);s.executeUpdate();}
                for(String table:new String[]{"war_defeated_towns","war_defeated_civs"})try(var s=db.prepareStatement("UPDATE "+table+" SET conqueror=? WHERE war_start=? AND conqueror=?")){s.setLong(1,civ);s.setLong(2,war);s.setLong(3,defender);s.executeUpdate();}
                try(var s=db.prepareStatement("INSERT INTO war_defeated_civs VALUES(?,?,?)")){s.setLong(1,war);s.setLong(2,defender);s.setLong(3,civ);s.executeUpdate();}
                try(var s=db.prepareStatement("INSERT INTO war_defeated_towns SELECT ?,id,? FROM towns WHERE civ_id=? ON CONFLICT(war_start,town_id) DO UPDATE SET conqueror=excluded.conqueror")){s.setLong(1,war);s.setLong(2,civ);s.setLong(3,defender);s.executeUpdate();}
            }else try(var s=db.prepareStatement("INSERT INTO war_defeated_towns VALUES(?,?,?)")){s.setLong(1,war);s.setLong(2,defenderTown);s.setLong(3,civ);s.executeUpdate();}
        }
        return new Hit(war,point,after,defeated,defeated&&capitol);
    }
}
