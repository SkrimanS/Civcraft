package com.avrgaming.civcraft.modern.war;
import com.avrgaming.civcraft.modern.diplomacy.DiplomacyStore;
import java.sql.*;
import java.util.UUID;
public final class WarPvpStore {
    private WarPvpStore() {}
    public enum Decision { ALLOWED,NOT_AT_WAR,NEUTRAL_IN_WARZONE,NON_PVP_ZONE }
    public static Decision decide(boolean claimed,boolean outlaw,boolean activeWarZone,boolean defenderParticipant,boolean attackerParticipant,boolean enemies,boolean defenderHasTown) {
        if(!claimed || outlaw)return Decision.ALLOWED;
        if(activeWarZone && !defenderParticipant)return Decision.ALLOWED;
        if(enemies)return Decision.ALLOWED;
        if(activeWarZone && !attackerParticipant)return Decision.NEUTRAL_IN_WARZONE;
        return defenderHasTown?Decision.NOT_AT_WAR:Decision.NON_PVP_ZONE;
    }
    private static Long member(Connection db,UUID player)throws SQLException {
        try(var s=db.prepareStatement("SELECT t.civ_id FROM residents r JOIN towns t ON t.id=r.town_id AND t.civ_id=r.civ_id WHERE r.uuid=?")){s.setString(1,player.toString());try(var r=s.executeQuery()){return r.next()?r.getLong(1):null;}}
    }
    public static Decision check(Connection db,UUID attacker,UUID defender,String world,int x,int z,long now)throws SQLException {
        long town,owner;
        try(var s=db.prepareStatement("SELECT t.id,t.civ_id FROM town_claims p JOIN towns t ON t.id=p.town_id WHERE p.world=? AND p.chunk_x=? AND p.chunk_z=?")){s.setString(1,world);s.setInt(2,x);s.setInt(3,z);try(var r=s.executeQuery()){if(!r.next())return Decision.ALLOWED;town=r.getLong(1);owner=r.getLong(2);}}
        Long a=member(db,attacker),d=member(db,defender);
        boolean active=WarClockStore.active(db,now)&&WarClockStore.hasWars(db,owner);
        boolean defenderParticipant=d!=null&&(d==owner || DiplomacyStore.status(db,d,owner)==DiplomacyStore.Status.WAR);
        boolean attackerParticipant=a!=null&&(a==owner || DiplomacyStore.status(db,a,owner)==DiplomacyStore.Status.WAR);
        boolean enemies=a!=null&&d!=null&&DiplomacyStore.status(db,a,d)==DiplomacyStore.Status.WAR;
        return decide(true,OutlawStore.active(db,town,attacker,now)||OutlawStore.active(db,town,defender,now),active,defenderParticipant,attackerParticipant,enemies,d!=null);
    }
}
