package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.util.*;
/** One legacy RegenTimer cycle (five seconds), with no offline catch-up. */
public final class BuildingRegenerationStore {
    private BuildingRegenerationStore() {}
    @FunctionalInterface public interface Validity { boolean valid(Connection db,long build)throws SQLException; }
    public static int tick(Connection db,Map<String,Integer> baseRates,int wonderRate,Validity validity)throws SQLException {
        if(wonderRate<0 || baseRates.values().stream().anyMatch(rate->rate<0))throw new IllegalArgumentException("Negative building regeneration");
        record Target(long id,String type,int hp,int maximum,boolean chichen) {}
        var targets=new ArrayList<Target>();
        try(var s=db.createStatement();var r=s.executeQuery("SELECT b.id,b.structure_id,h.hitpoints,h.maximum,EXISTS(SELECT 1 FROM structure_builds w JOIN towns wt ON wt.id=w.town_id WHERE wt.civ_id=t.civ_id AND w.structure_id='w_chichen_itza' AND w.status='COMPLETE') AS chichen FROM building_combat_health h JOIN structure_builds b ON b.id=h.build_id JOIN towns t ON t.id=b.town_id WHERE b.status IN ('QUEUED','BUILDING','COMPLETE') AND h.phase='DAMAGED' AND h.hitpoints>0")) {
            while(r.next())targets.add(new Target(r.getLong(1),r.getString(2),r.getInt(3),r.getInt(4),r.getBoolean(5)));
        }
        int updated=0;
        for(var target:targets) {
            long rate=(long)baseRates.getOrDefault(target.type(),0)+(target.chichen()?wonderRate:0);
            if(rate==0 || !validity.valid(db,target.id()))continue;
            int maximum=TowerHealthStore.maximum(db,target.id(),target.maximum());if(target.hp()==maximum)continue;
            int hp=(int)Math.min(maximum,target.hp()+rate);
            try(var s=db.prepareStatement("UPDATE building_combat_health SET hitpoints=?,maximum=? WHERE build_id=? AND hitpoints=? AND phase='DAMAGED'")){s.setInt(1,hp);s.setInt(2,maximum);s.setLong(3,target.id());s.setInt(4,target.hp());updated+=s.executeUpdate();}
        }
        return updated;
    }
}
