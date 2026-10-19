package com.avrgaming.civcraft.modern.war;
import java.sql.*;
/** Base marker health remains immutable; wonder ownership determines its extra capacity. */
public final class ControlPointBonusStore {
    private ControlPointBonusStore() {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS war_control_bonuses(build_id INTEGER PRIMARY KEY REFERENCES structure_builds(id) ON DELETE CASCADE,bonus INTEGER NOT NULL CHECK(bonus>=0))");}
    }
    public static int bonus(Connection db,long build)throws SQLException {
        try(var s=db.prepareStatement("SELECT bonus FROM war_control_bonuses WHERE build_id=?")){s.setLong(1,build);try(var r=s.executeQuery()){return r.next()?r.getInt(1):0;}}
    }
    public static void reconcile(Connection db,int extra,long now)throws SQLException {
        if(extra<0)throw new IllegalArgumentException("Бонус контрольной точки не может быть отрицательным");
        // Preserve current battle HP before capacity changes, including untouched points.
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO war_control_health SELECT w.start_at,p.world,p.x,p.y,p.z,p.maximum+COALESCE(b.bonus,0) FROM war_control_points p LEFT JOIN war_control_bonuses b ON b.build_id=p.build_id JOIN war_sessions w ON w.state='ACTIVE' AND w.start_at<=? AND w.end_at>?")){s.setLong(1,now);s.setLong(2,now);s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO war_control_bonuses SELECT h.id,CASE WHEN EXISTS(SELECT 1 FROM structure_builds wonder WHERE wonder.town_id=h.town_id AND wonder.structure_id='w_chichen_itza' AND wonder.status='COMPLETE') THEN ? ELSE 0 END FROM structure_builds h WHERE h.structure_id IN ('s_townhall','s_capitol') ON CONFLICT(build_id) DO UPDATE SET bonus=excluded.bonus")){s.setInt(1,extra);s.executeUpdate();}
    }
}
