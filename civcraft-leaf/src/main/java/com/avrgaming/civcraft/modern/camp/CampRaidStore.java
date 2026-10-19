package com.avrgaming.civcraft.modern.camp;

import java.sql.*;

/** Persistent daily camp window and source control health, separate from city wars. */
public final class CampRaidStore {
    private CampRaidStore() {}
    public static void install(Connection db,int base)throws SQLException {
        if(db.getAutoCommit() || base<1)throw new SQLException("Camp raid setup requires valid health and a transaction");
        boolean maximum=false,raid=false;
        try(var s=db.createStatement();var r=s.executeQuery("PRAGMA table_info(camps)")){while(r.next()){maximum|=r.getString("name").equals("control_max_hp");raid|=r.getString("name").equals("next_raid_at");}}
        try(var s=db.createStatement()) {
            if(!maximum)s.executeUpdate("ALTER TABLE camps ADD COLUMN control_max_hp INTEGER NOT NULL DEFAULT 50");
            if(!raid)s.executeUpdate("ALTER TABLE camps ADD COLUMN next_raid_at INTEGER NOT NULL DEFAULT 0");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS camp_raid_config(id INTEGER PRIMARY KEY CHECK(id=1),base_hp INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS camp_raid_legacy_health(camp_id INTEGER PRIMARY KEY,health INTEGER NOT NULL,maximum INTEGER NOT NULL)");
            int old=50;boolean first=true;try(var r=s.executeQuery("SELECT base_hp FROM camp_raid_config WHERE id=1")){if(r.next()){old=r.getInt(1);first=false;}}
            if(first)s.executeUpdate("INSERT OR IGNORE INTO camp_raid_legacy_health SELECT id,control_hp,control_max_hp FROM camps");
            // Preserve NPC bonuses and damage. A dead control block must never be resurrected.
            long delta=(long)base-old;
            if(delta!=0)s.executeUpdate("UPDATE camps SET control_hp=CASE WHEN control_hp<=0 THEN 0 ELSE MIN(MAX(1,control_max_hp+("+delta+")),MAX(1,control_hp+("+delta+"))) END,control_max_hp=MAX(1,control_max_hp+("+delta+"))");
            s.executeUpdate("INSERT INTO camp_raid_config(id,base_hp) VALUES(1,"+base+") ON CONFLICT(id) DO UPDATE SET base_hp=excluded.base_hp");
            s.executeUpdate("UPDATE camps SET next_raid_at=created_at+86400000 WHERE next_raid_at=0");
            s.executeUpdate("DROP TRIGGER IF EXISTS initialize_native_camp_raid");
            s.executeUpdate("CREATE TRIGGER initialize_native_camp_raid AFTER INSERT ON camps BEGIN UPDATE camps SET control_hp="+base+",control_max_hp="+base+",next_raid_at=NEW.created_at+86400000 WHERE id=NEW.id; END");
        }
    }
    public static CampRaidRules.Window window(Connection db,long camp,long duration,long now)throws SQLException {
        long start;try(var s=db.prepareStatement("SELECT next_raid_at FROM camps WHERE id=?")){s.setLong(1,camp);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Camp raid no longer exists");start=r.getLong(1);}}
        var window=CampRaidRules.window(start,duration,now);
        if(start!=window.start())try(var s=db.prepareStatement("UPDATE camps SET next_raid_at=? WHERE id=? AND next_raid_at=?")){s.setLong(1,window.start());s.setLong(2,camp);s.setLong(3,start);if(s.executeUpdate()!=1)throw new SQLException("Camp raid clock changed");}
        return window;
    }
    public record Hit(int health,int maximum) {}
    public static Hit hit(Connection db,long camp,long duration,long now)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Camp raid hit requires a transaction");
        CampLonghouseStore.requireUnlocked(db,camp);
        if(!window(db,camp,duration,now).open())throw new IllegalArgumentException("Сейчас лагерь защищён от рейда.");
        try(var s=db.prepareStatement("UPDATE camps SET control_hp=MAX(0,control_hp-1) WHERE id=? AND control_hp>0")){s.setLong(1,camp);if(s.executeUpdate()!=1)throw new IllegalArgumentException("Контрольный блок лагеря уже разрушен.");}
        try(var s=db.prepareStatement("SELECT control_hp,control_max_hp FROM camps WHERE id=?")){s.setLong(1,camp);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Camp disappeared during hit");return new Hit(r.getInt(1),r.getInt(2));}}
    }
}
