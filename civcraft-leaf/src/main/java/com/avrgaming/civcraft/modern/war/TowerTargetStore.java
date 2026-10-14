package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.util.UUID;
import com.avrgaming.civcraft.modern.diplomacy.DiplomacyStore;
public final class TowerTargetStore {
    private TowerTargetStore() {}
    public static boolean eligible(Connection db,long town,UUID player,long now)throws SQLException {
        if(OutlawStore.active(db,town,player,now))return true;
        long owner,target;
        try(var s=db.prepareStatement("SELECT own.civ_id,other.civ_id FROM towns own JOIN residents r ON r.uuid=? JOIN towns other ON other.id=r.town_id AND other.civ_id=r.civ_id WHERE own.id=?")){s.setString(1,player.toString());s.setLong(2,town);try(var r=s.executeQuery()){if(!r.next())return false;owner=r.getLong(1);target=r.getLong(2);}}
        if(owner==target)return false;var status=DiplomacyStore.status(db,owner,target);return status==DiplomacyStore.Status.WAR||status==DiplomacyStore.Status.HOSTILE;
    }
    public static boolean friendly(Connection db,long town,UUID player)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM towns own JOIN residents r ON r.uuid=? JOIN towns other ON other.id=r.town_id AND other.civ_id=r.civ_id WHERE own.id=? AND own.civ_id=other.civ_id")){s.setString(1,player.toString());s.setLong(2,town);try(var r=s.executeQuery()){return r.next();}}
    }
    public static boolean range(double distanceSquared,double minimum,double maximum) {
        return Double.isFinite(distanceSquared)&&distanceSquared>minimum*minimum&&distanceSquared<=maximum*maximum;
    }
    public static double range(Connection db,long town,double base,double lighthouse)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM structure_builds WHERE town_id=? AND structure_id='w_great_lighthouse' AND status='COMPLETE' LIMIT 1")){s.setLong(1,town);try(var r=s.executeQuery()){return r.next()?base*lighthouse:base;}}
    }
}
