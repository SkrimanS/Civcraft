package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.util.*;
import com.avrgaming.civcraft.modern.diplomacy.DiplomacyStore;
public final class ScoutStore {
    private ScoutStore() {}
    public enum Threat { NEUTRAL,HOSTILE,WAR,OUTLAW }
    public static void install(Connection db)throws SQLException {
        OutlawStore.install(db);try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS scout_preferences(uuid TEXT PRIMARY KEY REFERENCES residents(uuid) ON DELETE CASCADE,enabled INTEGER NOT NULL CHECK(enabled IN (0,1)))");}
    }
    public static Threat threat(Connection db,long town,UUID player,long now)throws SQLException {
        if(OutlawStore.active(db,town,player,now))return Threat.OUTLAW;
        long owner;Long target=null;
        try(var s=db.prepareStatement("SELECT own.civ_id,other.civ_id FROM towns own LEFT JOIN residents r ON r.uuid=? LEFT JOIN towns other ON other.id=r.town_id AND other.civ_id=r.civ_id WHERE own.id=?")){s.setString(1,player.toString());s.setLong(2,town);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Scout town not found");owner=r.getLong(1);if(r.getObject(2)!=null)target=r.getLong(2);}}
        if(target==null)return Threat.NEUTRAL;if(owner==target)return null;
        return switch(DiplomacyStore.status(db,owner,target)){case PEACE,ALLY->null;case HOSTILE->Threat.HOSTILE;case WAR->Threat.WAR;default->Threat.NEUTRAL;};
    }
    public static boolean enabled(Connection db,UUID player)throws SQLException {
        try(var s=db.prepareStatement("SELECT enabled FROM scout_preferences WHERE uuid=?")){s.setString(1,player.toString());try(var r=s.executeQuery()){return !r.next()||r.getBoolean(1);}}
    }
    public static boolean toggle(Connection db,UUID player)throws SQLException {
        boolean next=!enabled(db,player);try(var s=db.prepareStatement("INSERT INTO scout_preferences VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET enabled=excluded.enabled")){s.setString(1,player.toString());s.setBoolean(2,next);s.executeUpdate();}return next;
    }
    public static List<UUID> recipients(Connection db,long civ)throws SQLException {
        var result=new ArrayList<UUID>();try(var s=db.prepareStatement("SELECT r.uuid FROM residents r JOIN towns t ON t.id=r.town_id AND t.civ_id=r.civ_id LEFT JOIN scout_preferences p ON p.uuid=r.uuid WHERE t.civ_id=? AND COALESCE(p.enabled,1)=1")){s.setLong(1,civ);try(var r=s.executeQuery()){while(r.next())result.add(UUID.fromString(r.getString(1)));}}return result;
    }
}
