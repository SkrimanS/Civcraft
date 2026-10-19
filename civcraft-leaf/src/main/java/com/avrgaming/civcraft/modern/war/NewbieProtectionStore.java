package com.avrgaming.civcraft.modern.war;

import java.sql.*;
import java.util.*;

/** Original registration-time PvP protection with a durable voluntary opt-out. */
public final class NewbieProtectionStore {
    private NewbieProtectionStore() {}
    public static void install(Connection db,long now)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Newbie protection installation requires a transaction");
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS resident_pvp_protection(uuid TEXT PRIMARY KEY REFERENCES residents(uuid) ON DELETE CASCADE,registered_at INTEGER NOT NULL,disabled_at INTEGER,ended_notice INTEGER NOT NULL DEFAULT 0)");
            // Existing profiles must not receive a fresh shield when the module is installed.
            s.executeUpdate("INSERT OR IGNORE INTO resident_pvp_protection(uuid,registered_at,disabled_at,ended_notice) SELECT uuid,created_at,"+now+",1 FROM residents");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS initialize_resident_pvp_protection AFTER INSERT ON residents BEGIN INSERT OR IGNORE INTO resident_pvp_protection(uuid,registered_at) VALUES(NEW.uuid,NEW.created_at); END");
        }
    }
    public static boolean active(Connection db,UUID player,long duration,long now)throws SQLException {
        if(duration<=0)return false;
        try(var s=db.prepareStatement("SELECT registered_at,disabled_at FROM resident_pvp_protection WHERE uuid=?")){s.setString(1,player.toString());try(var r=s.executeQuery()){return r.next() && r.getObject(2)==null && now-r.getLong(1)<=duration;}}
    }
    public static boolean disable(Connection db,UUID player,long duration,long now)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Protection opt-out requires a transaction");
        boolean active=active(db,player,duration,now);
        try(var s=db.prepareStatement("UPDATE resident_pvp_protection SET disabled_at=COALESCE(disabled_at,?),ended_notice=1 WHERE uuid=?")){s.setLong(1,now);s.setString(2,player.toString());s.executeUpdate();}return active;
    }
    public static List<UUID> expire(Connection db,long duration,long now)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Protection expiry requires a transaction");
        var out=new ArrayList<UUID>();
        try(var s=db.prepareStatement("SELECT uuid FROM resident_pvp_protection WHERE disabled_at IS NULL AND (registered_at<? OR ?=0)")){s.setLong(1,now-duration);s.setLong(2,duration);try(var r=s.executeQuery()){while(r.next())out.add(UUID.fromString(r.getString(1)));}}
        try(var s=db.prepareStatement("UPDATE resident_pvp_protection SET disabled_at=?,ended_notice=1 WHERE uuid=? AND disabled_at IS NULL")){for(var uuid:out){s.setLong(1,now);s.setString(2,uuid.toString());s.addBatch();}s.executeBatch();}return List.copyOf(out);
    }
}
