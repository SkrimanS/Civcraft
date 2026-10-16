package com.avrgaming.civcraft.modern.build;

import java.sql.*;

/** Enabled is independent of construction, destruction, ownership and physical protection. */
public final class BuildingActivityStore {
    private BuildingActivityStore() {}
    public static void install(Connection db)throws SQLException{
        try(var s=db.createStatement()){
            s.executeUpdate("CREATE TABLE IF NOT EXISTS structure_activity(build_id INTEGER PRIMARY KEY REFERENCES structure_builds(id) ON DELETE CASCADE,town_id INTEGER NOT NULL,enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),reason TEXT NOT NULL DEFAULT '')");
            s.executeUpdate("CREATE VIEW IF NOT EXISTS active_structure_builds AS SELECT b.* FROM structure_builds b WHERE b.status='COMPLETE' AND COALESCE((SELECT a.enabled FROM structure_activity a WHERE a.build_id=b.id),1)=1");
        }
    }
    private static boolean table(Connection db,String name)throws SQLException{try(var s=db.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")){s.setString(1,name);try(var r=s.executeQuery()){return r.next();}}}
    public static boolean pending(Connection db,long build)throws SQLException{
        for(String name:new String[]{"resource_production_operations","consumption_receipts","pasture_animals","trade_ship_operations"})if(table(db,name)){
            String state=name.equals("pasture_animals")?"state='PENDING'":"status='PREPARED'";
            try(var s=db.prepareStatement("SELECT 1 FROM "+name+" WHERE build_id=? AND "+state+" LIMIT 1")){s.setLong(1,build);try(var r=s.executeQuery()){if(r.next())return true;}}
        }
        if(table(db,"resource_production_plantings"))try(var s=db.prepareStatement("SELECT 1 FROM resource_production_plantings WHERE farm_id=? LIMIT 1")){s.setLong(1,build);try(var r=s.executeQuery()){if(r.next())return true;}}
        if(table(db,"stable_deliveries"))try(var s=db.prepareStatement("SELECT 1 FROM stable_deliveries d JOIN trade_receipts r ON r.id=d.trade_id WHERE d.build_id=? AND r.status='PREPARED' LIMIT 1")){s.setLong(1,build);try(var r=s.executeQuery()){if(r.next())return true;}}
        if(table(db,"goodie_transfers") && table(db,"goodie_frames"))try(var s=db.prepareStatement("SELECT 1 FROM goodie_transfers t LEFT JOIN goodie_frames f ON f.frame_key=t.from_frame LEFT JOIN goodie_frames target ON target.frame_key=t.to_frame WHERE t.status='PREPARED' AND (t.origin_build=? OR f.build_id=? OR target.build_id=?) LIMIT 1")){s.setLong(1,build);s.setLong(2,build);s.setLong(3,build);try(var r=s.executeQuery()){if(r.next())return true;}}
        return false;
    }
    public static boolean enabled(Connection db,long build)throws SQLException{
        try(var s=db.prepareStatement("SELECT enabled FROM structure_activity WHERE build_id=?")){s.setLong(1,build);try(var r=s.executeQuery()){return !r.next() || r.getBoolean(1);}}
    }
    public static boolean set(Connection db,long build,long town,boolean enabled,String reason,long now)throws SQLException{
        if(db.getAutoCommit())throw new SQLException("Activity change requires a transaction");boolean changed=enabled(db,build)!=enabled;
        if(changed && pending(db,build))return false;
        try(var s=db.prepareStatement("INSERT INTO structure_activity VALUES(?,?,?,?) ON CONFLICT(build_id) DO UPDATE SET town_id=excluded.town_id,enabled=excluded.enabled,reason=excluded.reason")){s.setLong(1,build);s.setLong(2,town);s.setBoolean(3,enabled);s.setString(4,reason);s.executeUpdate();}
        if(changed){
            if(table(db,"resource_production_clock"))try(var s=db.prepareStatement("UPDATE resource_production_clock SET last_tick=MAX(last_tick,?/period) WHERE build_id=? AND EXISTS(SELECT 1 FROM structure_builds WHERE id=? AND structure_id<>'ti_farm')")){s.setLong(1,now);s.setLong(2,build);s.setLong(3,build);s.executeUpdate();}
            if(table(db,"farm_growth_state"))try(var s=db.prepareStatement("UPDATE farm_growth_state SET last_tick=?/period WHERE build_id=?")){s.setLong(1,now);s.setLong(2,build);s.executeUpdate();}
            if(table(db,"building_consumption"))try(var s=db.prepareStatement("UPDATE building_consumption SET last_hour=MAX(last_hour,?/period) WHERE build_id=?")){s.setLong(1,now);s.setLong(2,build);s.executeUpdate();}
            if(table(db,"trade_ship_clock"))try(var s=db.prepareStatement("UPDATE trade_ship_clock SET last_hour=MAX(last_hour,?/period) WHERE build_id=?")){s.setLong(1,now);s.setLong(2,build);s.executeUpdate();}
        }return true;
    }
}
