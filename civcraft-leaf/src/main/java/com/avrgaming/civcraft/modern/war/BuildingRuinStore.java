package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.util.*;
public final class BuildingRuinStore {
    private BuildingRuinStore() {}
    public record Change(String world,int x,int y,int z,String before,String after,boolean burst) {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS building_ruin_jobs(build_id INTEGER PRIMARY KEY REFERENCES structure_builds(id) ON DELETE CASCADE,state TEXT NOT NULL CHECK(state IN ('PREPARED','COMPLETE')))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS building_ruin_blocks(build_id INTEGER NOT NULL REFERENCES building_ruin_jobs(build_id) ON DELETE CASCADE,world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,before_data TEXT NOT NULL,after_data TEXT NOT NULL,burst INTEGER NOT NULL,PRIMARY KEY(build_id,world,x,y,z))");
        }
    }
    public static Long next(Connection db)throws SQLException {
        try(var s=db.createStatement();var r=s.executeQuery("SELECT b.id FROM structure_builds b JOIN building_combat_health h ON h.build_id=b.id WHERE b.status='WAR_DESTROY_PENDING' AND h.hitpoints=0 AND h.phase='DESTROY_PENDING' ORDER BY b.id LIMIT 1")){return r.next()?r.getLong(1):null;}
    }
    public static List<Change> changes(Connection db,long build)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM building_ruin_jobs WHERE build_id=?")) {
            s.setLong(1,build);try(var r=s.executeQuery()){if(!r.next())return null;}
        }
        var result=new ArrayList<Change>();
        try(var s=db.prepareStatement("SELECT * FROM building_ruin_blocks WHERE build_id=? ORDER BY world,x,y,z")) {
            s.setLong(1,build);try(var r=s.executeQuery()){while(r.next())result.add(new Change(r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getString("before_data"),r.getString("after_data"),r.getBoolean("burst")));}
        }return List.copyOf(result);
    }
    public static void prepare(Connection db,long build,List<Change> changes)throws SQLException {
        com.avrgaming.civcraft.modern.trade.TradeShipOperations.requireBuildUnlocked(db,build);
        try(var s=db.prepareStatement("SELECT 1 FROM structure_builds b JOIN building_combat_health h ON h.build_id=b.id WHERE b.id=? AND b.status='WAR_DESTROY_PENDING' AND h.hitpoints=0 AND h.phase='DESTROY_PENDING'")) {
            s.setLong(1,build);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Building no longer awaits destruction");}
        }
        if(changes(db,build)!=null)throw new SQLException("Ruin plan already exists");
        try(var s=db.prepareStatement("INSERT INTO building_ruin_jobs VALUES(?,'PREPARED')")){s.setLong(1,build);s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO building_ruin_blocks VALUES(?,?,?,?,?,?,?,?)")) {
            for(var c:changes) {
                try(var check=db.prepareStatement("SELECT 1 FROM protected_blocks WHERE build_id=? AND world=? AND x=? AND y=? AND z=?")) {
                    check.setLong(1,build);check.setString(2,c.world());check.setInt(3,c.x());check.setInt(4,c.y());check.setInt(5,c.z());try(var r=check.executeQuery()){if(!r.next())throw new SQLException("Ruin plan contains another building's block");}
                }
                s.setLong(1,build);s.setString(2,c.world());s.setInt(3,c.x());s.setInt(4,c.y());s.setInt(5,c.z());s.setString(6,c.before());s.setString(7,c.after());s.setBoolean(8,c.burst());s.addBatch();
            }s.executeBatch();
        }
    }
    public static boolean complete(Connection db,long build)throws SQLException {
        com.avrgaming.civcraft.modern.trade.TradeShipOperations.requireBuildUnlocked(db,build);
        try(var s=db.prepareStatement("SELECT state FROM building_ruin_jobs WHERE build_id=?")) {
            s.setLong(1,build);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing ruin plan");if(r.getString(1).equals("COMPLETE"))return false;}
        }
        try(var s=db.prepareStatement("UPDATE building_combat_health SET phase='DESTROYED' WHERE build_id=? AND hitpoints=0 AND phase='DESTROY_PENDING'")){s.setLong(1,build);if(s.executeUpdate()!=1)throw new SQLException("Building health changed before ruin completion");}
        try(var s=db.prepareStatement("UPDATE structure_builds SET status='WAR_DESTROYED' WHERE id=? AND status='WAR_DESTROY_PENDING'")){s.setLong(1,build);if(s.executeUpdate()!=1)throw new SQLException("Building changed before ruin completion");}
        com.avrgaming.civcraft.modern.trade.TradeShipOperations.resetDestroyed(db,build);
        try(var s=db.prepareStatement("UPDATE building_ruin_jobs SET state='COMPLETE' WHERE build_id=?")){s.setLong(1,build);s.executeUpdate();}return true;
    }
}
