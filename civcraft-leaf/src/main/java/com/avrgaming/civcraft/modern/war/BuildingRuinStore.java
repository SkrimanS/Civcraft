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
            s.executeUpdate("CREATE TABLE IF NOT EXISTS wonder_sabotage_targets(build_id INTEGER PRIMARY KEY REFERENCES structure_builds(id) ON DELETE CASCADE)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS wonder_sabotage_previews(build_id INTEGER NOT NULL REFERENCES wonder_sabotage_targets(build_id) ON DELETE CASCADE,world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,data TEXT NOT NULL,PRIMARY KEY(build_id,world,x,y,z))");
        }
    }
    public static void sabotageWonder(Connection db,long build,com.avrgaming.civcraft.modern.build.BuildQueue.DestructionSnapshot snapshot)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Wonder sabotage requires a transaction");
        int maximum;try(var s=db.prepareStatement("SELECT max_hitpoints FROM structure_builds WHERE id=? AND structure_id GLOB 'w_*' AND status IN ('QUEUED','BUILDING')")){s.setLong(1,build);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Чудо уже завершено или уничтожено.");maximum=Math.max(1,r.getInt(1));}}
        try(var s=db.prepareStatement("INSERT INTO wonder_sabotage_targets VALUES(?)")){s.setLong(1,build);s.executeUpdate();}
        var placements=new ArrayList<>(snapshot.placements());
        for(var preview:snapshot.previews()){
            placements.add(new com.avrgaming.civcraft.modern.build.BlockPlacement(preview.world(),preview.x(),preview.y(),preview.z(),"BEDROCK"));
            try(var s=db.prepareStatement("INSERT INTO wonder_sabotage_previews VALUES(?,?,?,?,?,?)")){s.setLong(1,build);s.setString(2,preview.world());s.setInt(3,preview.x());s.setInt(4,preview.y());s.setInt(5,preview.z());s.setString(6,preview.blockData());s.executeUpdate();}
        }
        for(var placement:placements){
            try(var s=db.prepareStatement("SELECT 1 FROM structure_chunks WHERE build_id=? AND world=? AND chunk_x=? AND chunk_z=?")){s.setLong(1,build);s.setString(2,placement.world());s.setInt(3,placement.x()>>4);s.setInt(4,placement.z()>>4);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Sabotage block outside reserved build footprint");}}
            try(var s=db.prepareStatement("SELECT build_id FROM protected_blocks WHERE world=? AND x=? AND y=? AND z=?")){s.setString(1,placement.world());s.setInt(2,placement.x());s.setInt(3,placement.y());s.setInt(4,placement.z());try(var r=s.executeQuery()){if(r.next() && r.getLong(1)!=build)throw new SQLException("Another building occupies a sabotage block");}}
            try(var s=db.prepareStatement("INSERT OR IGNORE INTO protected_blocks(world,x,y,z,build_id,material) VALUES(?,?,?,?,?,?)")){s.setString(1,placement.world());s.setInt(2,placement.x());s.setInt(3,placement.y());s.setInt(4,placement.z());s.setLong(5,build);s.setString(6,placement.material());s.executeUpdate();}
        }
        try(var s=db.prepareStatement("INSERT INTO building_combat_health VALUES(?,0,?,'DESTROY_PENDING') ON CONFLICT(build_id) DO UPDATE SET hitpoints=0,maximum=excluded.maximum,phase='DESTROY_PENDING'")){s.setLong(1,build);s.setInt(2,maximum);s.executeUpdate();}
        try(var s=db.prepareStatement("UPDATE structure_builds SET status='WAR_DESTROY_PENDING' WHERE id=? AND status IN ('QUEUED','BUILDING')")){s.setLong(1,build);if(s.executeUpdate()!=1)throw new SQLException("Wonder changed during sabotage");}
    }
    public static List<com.avrgaming.civcraft.modern.build.BlockBackup> sabotagePreviews(Connection db,long build)throws SQLException {
        var result=new ArrayList<com.avrgaming.civcraft.modern.build.BlockBackup>();try(var s=db.prepareStatement("SELECT world,x,y,z,data FROM wonder_sabotage_previews WHERE build_id=?")){s.setLong(1,build);try(var r=s.executeQuery()){while(r.next())result.add(new com.avrgaming.civcraft.modern.build.BlockBackup(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4),r.getString(5)));}}return result;
    }
    public static Long next(Connection db)throws SQLException {
        try(var s=db.createStatement();var r=s.executeQuery("SELECT b.id FROM structure_builds b JOIN building_combat_health h ON h.build_id=b.id WHERE b.status='WAR_DESTROY_PENDING' AND h.hitpoints=0 AND h.phase='DESTROY_PENDING' ORDER BY b.id LIMIT 1")){return r.next()?r.getLong(1):null;}
    }
    /** Completed Councils are removed on war defeat through the same durable ruin worker. */
    public static void destroyCompletedWonder(Connection db,long build)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Wonder destruction requires a transaction");
        int maximum;try(var s=db.prepareStatement("SELECT max_hitpoints FROM structure_builds WHERE id=? AND structure_id GLOB 'w_*' AND status='COMPLETE'")){s.setLong(1,build);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Completed wonder changed before destruction");maximum=Math.max(1,r.getInt(1));}}
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO wonder_sabotage_targets VALUES(?)")){s.setLong(1,build);s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO building_combat_health VALUES(?,0,?,'DESTROY_PENDING') ON CONFLICT(build_id) DO UPDATE SET hitpoints=0,maximum=excluded.maximum,phase='DESTROY_PENDING'")){s.setLong(1,build);s.setInt(2,maximum);s.executeUpdate();}
        try(var s=db.prepareStatement("UPDATE structure_builds SET status='WAR_DESTROY_PENDING' WHERE id=? AND status='COMPLETE'")){s.setLong(1,build);if(s.executeUpdate()!=1)throw new SQLException("Completed wonder changed before destruction");}
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
        com.avrgaming.civcraft.modern.town.ProductionStore.requireBuildUnlocked(db,build);
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
        com.avrgaming.civcraft.modern.town.ProductionStore.requireBuildUnlocked(db,build);
        try(var s=db.prepareStatement("SELECT state FROM building_ruin_jobs WHERE build_id=?")) {
            s.setLong(1,build);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing ruin plan");if(r.getString(1).equals("COMPLETE"))return false;}
        }
        try(var s=db.prepareStatement("UPDATE building_combat_health SET phase='DESTROYED' WHERE build_id=? AND hitpoints=0 AND phase='DESTROY_PENDING'")){s.setLong(1,build);if(s.executeUpdate()!=1)throw new SQLException("Building health changed before ruin completion");}
        try(var s=db.prepareStatement("UPDATE structure_builds SET status='WAR_DESTROYED' WHERE id=? AND status='WAR_DESTROY_PENDING'")){s.setLong(1,build);if(s.executeUpdate()!=1)throw new SQLException("Building changed before ruin completion");}
        com.avrgaming.civcraft.modern.trade.TradeShipOperations.resetDestroyed(db,build);
        com.avrgaming.civcraft.modern.town.ProductionStore.resetDestroyed(db,build);
        boolean sabotaged;try(var s=db.prepareStatement("SELECT 1 FROM wonder_sabotage_targets WHERE build_id=?")){s.setLong(1,build);try(var r=s.executeQuery()){sabotaged=r.next();}}
        if(sabotaged){
            for(String table:List.of("protected_blocks","structure_chunks"))try(var s=db.prepareStatement("DELETE FROM "+table+" WHERE build_id=?")){s.setLong(1,build);s.executeUpdate();}
            try(var s=db.prepareStatement("UPDATE structure_builds SET status='DEMOLISHED' WHERE id=? AND status='WAR_DESTROYED'")){s.setLong(1,build);if(s.executeUpdate()!=1)throw new SQLException("Sabotaged wonder changed before release");}
            try(var s=db.prepareStatement("DELETE FROM wonder_sabotage_previews WHERE build_id=?")){s.setLong(1,build);s.executeUpdate();}
        }
        try(var s=db.prepareStatement("UPDATE building_ruin_jobs SET state='COMPLETE' WHERE build_id=?")){s.setLong(1,build);s.executeUpdate();}return true;
    }
}
