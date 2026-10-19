package com.avrgaming.civcraft.modern.wonder;
import java.sql.*;
import java.util.*;
import com.avrgaming.civcraft.modern.town.UpkeepStore;
public final class ColossusLeechStore {
    private ColossusLeechStore() {}
    public record Source(long build,long town,long civ) {}
    public record Hall(String world,int x,int y,int z) {
        public double distance(Hall other){return world.equals(other.world())?Math.pow((double)x-other.x(),2)+Math.pow((double)y-other.y(),2)+Math.pow((double)z-other.z(),2):Double.MAX_VALUE;}
    }
    public static void install(Connection db)throws SQLException {try(var s=db.createStatement()) {
        s.executeUpdate("CREATE TABLE IF NOT EXISTS colossus_sources(build_id INTEGER PRIMARY KEY REFERENCES structure_builds(id) ON DELETE CASCADE,owner_civ INTEGER NOT NULL)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS colossus_targets(build_id INTEGER NOT NULL REFERENCES colossus_sources(build_id) ON DELETE CASCADE,civ_id INTEGER NOT NULL REFERENCES civilizations(id) ON DELETE CASCADE,distance REAL NOT NULL,PRIMARY KEY(build_id,civ_id))");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS colossus_leech_payments(day TEXT NOT NULL,source_town INTEGER NOT NULL,source_civ INTEGER NOT NULL,build_id INTEGER NOT NULL,target_town INTEGER NOT NULL,coins INTEGER NOT NULL,PRIMARY KEY(day,source_town))");
    }}
    public static void reconcile(Connection db,boolean reload)throws SQLException {
        try(var s=db.createStatement()){if(reload)s.executeUpdate("DELETE FROM colossus_sources");s.executeUpdate("DELETE FROM colossus_sources WHERE NOT EXISTS(SELECT 1 FROM structure_builds b WHERE b.id=build_id AND b.structure_id='w_colossus' AND b.status='COMPLETE')");}
        var sources=new ArrayList<Source>();try(var s=db.createStatement();var r=s.executeQuery("SELECT b.id,t.id,t.civ_id FROM structure_builds b JOIN towns t ON t.id=b.town_id WHERE b.structure_id='w_colossus' AND b.status='COMPLETE' ORDER BY b.id")){while(r.next())sources.add(new Source(r.getLong(1),r.getLong(2),r.getLong(3)));}
        for(var source:sources) {
            boolean unchanged;try(var s=db.prepareStatement("SELECT 1 FROM colossus_sources WHERE build_id=? AND owner_civ=?")){s.setLong(1,source.build());s.setLong(2,source.civ());try(var r=s.executeQuery()){unchanged=r.next();}}if(unchanged)continue;
            try(var s=db.prepareStatement("DELETE FROM colossus_sources WHERE build_id=?")){s.setLong(1,source.build());s.executeUpdate();}
            try(var s=db.prepareStatement("INSERT INTO colossus_sources VALUES(?,?)")){s.setLong(1,source.build());s.setLong(2,source.civ());s.executeUpdate();}
            var nearest=nearest(db,source.town(),source.civ());int selected=0;for(var entry:nearest.entrySet()) {
                if(selected++==4)break;try(var s=db.prepareStatement("INSERT INTO colossus_targets VALUES(?,?,?)")){s.setLong(1,source.build());s.setLong(2,entry.getValue());s.setDouble(3,entry.getKey());s.executeUpdate();}
            }
        }
    }
    public static NavigableMap<Double,Long> nearest(Connection db,long hostTown,long ownCiv)throws SQLException {
        var halls=new HashMap<Long,Hall>();try(var s=db.createStatement();var r=s.executeQuery("SELECT town_id,world,x,y,z FROM structure_builds WHERE structure_id IN ('s_townhall','s_capitol') AND status IN ('QUEUED','BUILDING','COMPLETE') ORDER BY id")){while(r.next())halls.putIfAbsent(r.getLong(1),new Hall(r.getString(2),r.getInt(3),r.getInt(4),r.getInt(5)));}
        var result=new TreeMap<Double,Long>();var origin=halls.get(hostTown);if(origin==null)return result;
        var distances=new TreeMap<Long,Double>();try(var s=db.prepareStatement("SELECT c.id,t.id FROM civilizations c LEFT JOIN towns t ON t.civ_id=c.id AND "+com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.visible(db,"towns","t.id")+" WHERE c.id<>? AND "+com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.visible(db,"civilizations","c.id")+" ORDER BY c.id,t.id")){s.setLong(1,ownCiv);try(var r=s.executeQuery()){while(r.next()){long civ=r.getLong(1);var hall=halls.get(r.getLong(2));double distance=hall==null?Double.MAX_VALUE:origin.distance(hall);distances.merge(civ,distance,Math::min);}}}
        distances.forEach((civ,distance)->result.put(distance,civ));return result;
    }
    public static long pay(Connection db,String day,UpkeepStore.Charge charge,double rate)throws SQLException {
        if(charge.amount()==0)return 0;
        long build,town;try(var s=db.prepareStatement("SELECT b.id,b.town_id FROM structure_builds b WHERE b.structure_id='w_colossus' AND b.status IN ('QUEUED','BUILDING','COMPLETE') AND EXISTS(SELECT 1 FROM colossus_targets WHERE civ_id=?) ORDER BY b.id LIMIT 1")){s.setLong(1,charge.civ());try(var r=s.executeQuery()){if(!r.next())return 0;build=r.getLong(1);town=r.getLong(2);}}
        long amount=WonderEconomy.coins(rate,charge.amount());try(var s=db.prepareStatement("INSERT OR IGNORE INTO colossus_leech_payments VALUES(?,?,?,?,?,?)")){s.setString(1,day);s.setLong(2,charge.town());s.setLong(3,charge.civ());s.setLong(4,build);s.setLong(5,town);s.setLong(6,amount);if(s.executeUpdate()!=1)return 0;}
        if(amount>0)UpkeepStore.deposit(db,"towns",town,amount);return amount;
    }
}
