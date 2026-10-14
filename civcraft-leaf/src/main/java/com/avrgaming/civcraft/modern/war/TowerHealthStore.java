package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.util.*;
public final class TowerHealthStore {
    private TowerHealthStore() {}
    public static final Set<String> TYPES=Set.of("s_arrowtower","s_cannontower","s_teslatower","s_scouttower");
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS tower_health_settings(id INTEGER PRIMARY KEY CHECK(id=1),wonder_rate REAL NOT NULL CHECK(wonder_rate>=0))");s.executeUpdate("INSERT OR IGNORE INTO tower_health_settings VALUES(1,0)");}
    }
    public static void configure(Connection db,double rate)throws SQLException {
        if(!Double.isFinite(rate)||rate<0)throw new IllegalArgumentException("Invalid tower health bonus");
        try(var s=db.prepareStatement("UPDATE tower_health_settings SET wonder_rate=? WHERE id=1")){s.setDouble(1,rate);s.executeUpdate();}
    }
    public static int maximum(int base,double rate) {
        double result=base*(1+rate);if(base<1||!Double.isFinite(result)||rate<0||result>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid tower maximum health");return (int)result;
    }
    public static int maximum(Connection db,long build,int fallback)throws SQLException {
        try(var s=db.prepareStatement("SELECT b.structure_id,b.max_hitpoints,EXISTS(SELECT 1 FROM structure_builds w JOIN towns wt ON wt.id=w.town_id WHERE wt.civ_id=t.civ_id AND w.structure_id='w_chichen_itza' AND w.status='COMPLETE'),settings.wonder_rate FROM structure_builds b JOIN towns t ON t.id=b.town_id CROSS JOIN tower_health_settings settings WHERE b.id=?")) {
            s.setLong(1,build);try(var r=s.executeQuery()){return r.next()&&TYPES.contains(r.getString(1))?maximum(r.getInt(2),r.getBoolean(3)?r.getDouble(4):0):fallback;}
        }
    }
    public static void initialize(Connection db,long build)throws SQLException {
        String type;int base;try(var s=db.prepareStatement("SELECT structure_id,max_hitpoints FROM structure_builds WHERE id=?")){s.setLong(1,build);try(var r=s.executeQuery()){if(!r.next())return;type=r.getString(1);base=r.getInt(2);}}
        if(!TYPES.contains(type))return;int maximum=maximum(db,build,base);
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO building_combat_health VALUES(?,?,?,'DAMAGED')")){s.setLong(1,build);s.setInt(2,maximum);s.setInt(3,maximum);s.executeUpdate();}
    }
    public static void preserveExisting(Connection db)throws SQLException {
        // Existing unrecorded HP used the stored base value before this migration.
        try(var s=db.createStatement()){s.executeUpdate("INSERT OR IGNORE INTO building_combat_health SELECT id,max_hitpoints,max_hitpoints,'DAMAGED' FROM structure_builds WHERE structure_id IN ('s_arrowtower','s_cannontower','s_teslatower','s_scouttower') AND max_hitpoints>0 AND status IN ('QUEUED','BUILDING','COMPLETE')");}
    }
}
