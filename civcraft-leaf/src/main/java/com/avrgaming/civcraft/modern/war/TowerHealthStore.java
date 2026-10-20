package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.util.*;
public final class TowerHealthStore {
    private TowerHealthStore() {}
    public static final Set<String> TYPES=Set.of("s_arrowtower","s_cannontower","s_teslatower","s_scouttower");
    private static boolean healthType(String type){return type!=null && (TYPES.contains(type) || type.equals("ti_wall") || type.equals("ti_fortifiedwall"));}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS tower_health_settings(id INTEGER PRIMARY KEY CHECK(id=1),wonder_rate REAL NOT NULL CHECK(wonder_rate>=0))");s.executeUpdate("INSERT OR IGNORE INTO tower_health_settings VALUES(1,0)");s.executeUpdate("CREATE TABLE IF NOT EXISTS tower_barricade_goods(good_id TEXT PRIMARY KEY,rate REAL NOT NULL CHECK(rate>=0))");}
    }
    public static void configureBarricade(Connection db,Map<String,Double> goods)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Barricade catalogue requires a transaction");
        try(var s=db.createStatement()){s.executeUpdate("DELETE FROM tower_barricade_goods");}
        try(var s=db.prepareStatement("INSERT INTO tower_barricade_goods VALUES(?,?)")){for(var entry:goods.entrySet()) {
            if(!Double.isFinite(entry.getValue())||entry.getValue()<0)throw new IllegalArgumentException("Неверный бонус торгового товара для баррикады");
            s.setString(1,entry.getKey());s.setDouble(2,entry.getValue());s.addBatch();
        }s.executeBatch();}
    }
    public static double barricade(Connection db,long town)throws SQLException {
        try(var s=db.createStatement();var r=s.executeQuery("SELECT 1 FROM tower_barricade_goods LIMIT 1")){if(!r.next())return 0;}
        // The original Barricade buff is nonstacking. Only canonical, unlocked hall goods apply.
        try(var s=db.prepareStatement("""
            SELECT COALESCE(MAX(config.rate),0) FROM bonus_goodies g JOIN tower_barricade_goods config ON config.good_id=g.good_id
            JOIN goodie_frames f ON f.frame_key=g.holder_id AND f.is_source=0
            JOIN structure_builds hall ON hall.id=f.build_id AND hall.town_id=g.host_town
            JOIN structure_builds origin ON origin.id=g.origin_build
            WHERE g.holder_kind='FRAME' AND g.host_town=? AND hall.status='COMPLETE'
              AND hall.structure_id IN ('s_capitol','s_townhall') AND origin.status='COMPLETE'
              AND NOT EXISTS(SELECT 1 FROM goodie_transfers transfer WHERE transfer.origin_build=g.origin_build AND transfer.status='PREPARED')
            """)){s.setLong(1,town);try(var r=s.executeQuery()){return r.next()?r.getDouble(1):0;}}
    }
    public static void configure(Connection db,double rate)throws SQLException {
        if(!Double.isFinite(rate)||rate<0)throw new IllegalArgumentException("Неверный бонус здоровья башни");
        try(var s=db.prepareStatement("UPDATE tower_health_settings SET wonder_rate=? WHERE id=1")){s.setDouble(1,rate);s.executeUpdate();}
    }
    public static int maximum(int base,double rate) {
        double result=base*(1+rate);if(base<1||!Double.isFinite(result)||rate<0||result>Integer.MAX_VALUE)throw new IllegalArgumentException("Неверное максимальное здоровье башни");return (int)result;
    }
    public static int maximum(Connection db,long build,int fallback)throws SQLException {
        try(var s=db.prepareStatement("SELECT b.structure_id,b.max_hitpoints,EXISTS(SELECT 1 FROM structure_builds w JOIN towns wt ON wt.id=w.town_id WHERE wt.civ_id=t.civ_id AND w.structure_id='w_chichen_itza' AND w.status='COMPLETE'),settings.wonder_rate,t.id FROM structure_builds b JOIN towns t ON t.id=b.town_id CROSS JOIN tower_health_settings settings WHERE b.id=?")) {
            // Legacy tower and wall overrides put Barricade inside the Chichen Itza condition.
            s.setLong(1,build);try(var r=s.executeQuery()){return r.next()&&healthType(r.getString(1))?maximum(r.getInt(2),r.getBoolean(3)?r.getDouble(4)+barricade(db,r.getLong(5)):0):fallback;}
        }
    }
    public static void initialize(Connection db,long build)throws SQLException {
        String type;int base;try(var s=db.prepareStatement("SELECT structure_id,max_hitpoints FROM structure_builds WHERE id=?")){s.setLong(1,build);try(var r=s.executeQuery()){if(!r.next())return;type=r.getString(1);base=r.getInt(2);}}
        if(!healthType(type))return;int maximum=maximum(db,build,base);
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO building_combat_health VALUES(?,?,?,'DAMAGED')")){s.setLong(1,build);s.setInt(2,maximum);s.setInt(3,maximum);s.executeUpdate();}
    }
    public static void preserveExisting(Connection db)throws SQLException {
        // Existing unrecorded HP used the stored base value before this migration.
        try(var s=db.createStatement()){s.executeUpdate("INSERT OR IGNORE INTO building_combat_health SELECT id,max_hitpoints,max_hitpoints,'DAMAGED' FROM structure_builds WHERE structure_id IN ('s_arrowtower','s_cannontower','s_teslatower','s_scouttower','ti_wall','ti_fortifiedwall') AND max_hitpoints>0 AND status IN ('QUEUED','BUILDING','COMPLETE')");}
    }
}
