package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import java.util.*;

/** Durable physical chest images for resource production. Caller owns the transaction. */
public final class ProductionStore {
    private ProductionStore() {}
    public record Clock(long build,long period,long tick) {}
    public record Pending(String id,Clock before,long tick,long town,int produced,byte[] containers) {
        public Pending{containers=containers.clone();}
        @Override public byte[] containers(){return containers.clone();}
    }
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS town_production_settings(town_id INTEGER PRIMARY KEY REFERENCES towns(id) ON DELETE CASCADE,fishery_level INTEGER NOT NULL DEFAULT 1 CHECK(fishery_level BETWEEN 1 AND 4))");
            boolean trommel=false;try(var r=s.executeQuery("PRAGMA table_info(town_production_settings)")){while(r.next())if(r.getString("name").equals("trommel_level"))trommel=true;}
            if(!trommel)s.executeUpdate("ALTER TABLE town_production_settings ADD COLUMN trommel_level INTEGER NOT NULL DEFAULT 1 CHECK(trommel_level BETWEEN 1 AND 4)");
            boolean quarry=false;try(var r=s.executeQuery("PRAGMA table_info(town_production_settings)")){while(r.next())if(r.getString("name").equals("quarry_level"))quarry=true;}
            if(!quarry)s.executeUpdate("ALTER TABLE town_production_settings ADD COLUMN quarry_level INTEGER NOT NULL DEFAULT 1 CHECK(quarry_level BETWEEN 1 AND 4)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS resource_production_clock(build_id INTEGER PRIMARY KEY REFERENCES structure_builds(id) ON DELETE CASCADE,period INTEGER NOT NULL,last_tick INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS resource_production_operations(id TEXT PRIMARY KEY,build_id INTEGER NOT NULL,town_id INTEGER NOT NULL,period INTEGER NOT NULL,before_tick INTEGER NOT NULL,tick INTEGER NOT NULL,produced INTEGER NOT NULL,containers BLOB NOT NULL,status TEXT NOT NULL DEFAULT 'PREPARED',UNIQUE(build_id,period,tick))");
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_pending_resource_production ON resource_production_operations(build_id) WHERE status='PREPARED'");
            WindmillStore.install(db);FarmGrowthStore.install(db);
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS production_blocks_build_delete BEFORE DELETE ON structure_builds WHEN EXISTS(SELECT 1 FROM resource_production_operations WHERE build_id=OLD.id AND status='PREPARED') BEGIN SELECT RAISE(ABORT,'Resource production is pending'); END");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS production_blocks_owner_change BEFORE UPDATE OF town_id ON structure_builds WHEN NEW.town_id IS NOT OLD.town_id AND EXISTS(SELECT 1 FROM resource_production_operations WHERE build_id=OLD.id AND status='PREPARED') BEGIN SELECT RAISE(ABORT,'Resource production is pending'); END");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS production_blocks_civilization_change BEFORE UPDATE OF civ_id ON towns WHEN NEW.civ_id IS NOT OLD.civ_id AND EXISTS(SELECT 1 FROM resource_production_operations WHERE town_id=OLD.id AND status='PREPARED') BEGIN SELECT RAISE(ABORT,'Resource production is pending'); END");
        }
    }
    private static void transaction(Connection db)throws SQLException {if(db.getAutoCommit())throw new SQLException("Production requires a transaction");}
    public static Clock clock(Connection db,long build)throws SQLException {
        try(var s=db.prepareStatement("SELECT period,last_tick FROM resource_production_clock WHERE build_id=?")){s.setLong(1,build);try(var r=s.executeQuery()){return r.next()?new Clock(build,r.getLong(1),r.getLong(2)):null;}}
    }
    public static Clock initialize(Connection db,long build,long period,long tick)throws SQLException {
        transaction(db);if(period<1000 || tick<0)throw new IllegalArgumentException("Invalid production clock");
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO resource_production_clock VALUES(?,?,?)")){s.setLong(1,build);s.setLong(2,period);s.setLong(3,tick);s.executeUpdate();}return clock(db,build);
    }
    public static void start(Connection db,long period,long tick)throws SQLException {
        start(db,null,period,tick);
    }
    public static void start(Connection db,String type,long period,long tick)throws SQLException {
        transaction(db);if(period<1000 || tick<0)throw new IllegalArgumentException("Invalid production clock");
        if(!pending(db).isEmpty())throw new SQLException("Production recovery must finish before clock starts");
        try(var s=db.prepareStatement("UPDATE resource_production_clock SET period=?,last_tick=CASE WHEN period<>? THEN ? ELSE MAX(last_tick,?) END"+(type==null?"":" WHERE build_id IN (SELECT id FROM structure_builds WHERE structure_id=?)"))){s.setLong(1,period);s.setLong(2,period);s.setLong(3,tick);s.setLong(4,tick);if(type!=null)s.setString(5,type);s.executeUpdate();}
    }
    public static Pending prepare(Connection db,String id,Clock before,long tick,int produced,byte[] containers)throws SQLException {
        transaction(db);
        if(id==null || id.isBlank() || tick<=before.tick() || produced<0 || containers==null || containers.length==0)throw new IllegalArgumentException("Invalid production operation");
        if(!before.equals(clock(db,before.build())))throw new SQLException("Production clock changed");
        long town;
        try(var s=db.prepareStatement("SELECT town_id FROM structure_builds WHERE id=? AND structure_id IN ('ti_fish_hatchery','s_trommel','ti_quarry','ti_windmill','ti_farm') AND status='COMPLETE' AND town_id IS NOT NULL")) {
            s.setLong(1,before.build());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Producer is not complete");town=r.getLong(1);}
        }
        try(var s=db.prepareStatement("INSERT INTO resource_production_operations VALUES(?,?,?,?,?,?,?,?,'PREPARED')")) {
            s.setString(1,id);s.setLong(2,before.build());s.setLong(3,town);s.setLong(4,before.period());s.setLong(5,before.tick());s.setLong(6,tick);s.setInt(7,produced);s.setBytes(8,containers);s.executeUpdate();
        }
        return new Pending(id,before,tick,town,produced,containers);
    }
    public static List<Pending> pending(Connection db)throws SQLException {
        var result=new ArrayList<Pending>();try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM resource_production_operations WHERE status='PREPARED' ORDER BY tick,build_id")){while(r.next())result.add(read(r));}return List.copyOf(result);
    }
    private static Pending read(ResultSet r)throws SQLException{return new Pending(r.getString("id"),new Clock(r.getLong("build_id"),r.getLong("period"),r.getLong("before_tick")),r.getLong("tick"),r.getLong("town_id"),r.getInt("produced"),r.getBytes("containers"));}
    public static boolean complete(Connection db,String id)throws SQLException {
        transaction(db);Pending operation;
        try(var s=db.prepareStatement("SELECT * FROM resource_production_operations WHERE id=?")){s.setString(1,id);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Unknown production operation");if(r.getString("status").equals("COMPLETE"))return false;operation=read(r);}}
        if(!operation.before().equals(clock(db,operation.before().build())))throw new SQLException("Production changed during recovery");
        FarmGrowthStore.complete(db,id);
        try(var s=db.prepareStatement("UPDATE resource_production_clock SET last_tick=? WHERE build_id=?")){s.setLong(1,operation.tick());s.setLong(2,operation.before().build());if(s.executeUpdate()!=1)throw new SQLException("Missing production clock");}
        try(var s=db.prepareStatement("UPDATE resource_production_operations SET status='COMPLETE',containers=X'' WHERE id=? AND status='PREPARED'")){s.setString(1,id);if(s.executeUpdate()!=1)throw new SQLException("Production operation changed");}
        try(var s=db.prepareStatement("DELETE FROM resource_production_plantings WHERE operation_id=?")){s.setString(1,id);s.executeUpdate();}return true;
    }
    public static void requireBuildUnlocked(Connection db,long build)throws SQLException {requireUnlocked(db,"build_id",build);}
    public static void resetDestroyed(Connection db,long build)throws SQLException {
        try(var s=db.createStatement();var r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='resource_production_clock'")){if(!r.next())return;}
        requireBuildUnlocked(db,build);
        try(var s=db.prepareStatement("DELETE FROM farm_growth_state WHERE build_id=? AND EXISTS(SELECT 1 FROM structure_builds WHERE id=? AND status='WAR_DESTROYED')")){s.setLong(1,build);s.setLong(2,build);s.executeUpdate();}
        try(var s=db.prepareStatement("DELETE FROM resource_production_clock WHERE build_id=? AND EXISTS(SELECT 1 FROM structure_builds WHERE id=? AND status='WAR_DESTROYED')")){s.setLong(1,build);s.setLong(2,build);s.executeUpdate();}
    }
    public static void requireTownUnlocked(Connection db,long town)throws SQLException {requireUnlocked(db,"town_id",town);}
    private static void requireUnlocked(Connection db,String column,long id)throws SQLException {
        try(var s=db.createStatement();var r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='resource_production_operations'")){if(!r.next())return;}
        String target=column.equals("build_id")?"p.farm_id":"f.town_id";
        try(var s=db.prepareStatement("SELECT 1 FROM resource_production_operations o WHERE o.status='PREPARED' AND (o."+column+"=? OR EXISTS(SELECT 1 FROM resource_production_plantings p JOIN structure_builds f ON f.id=p.farm_id WHERE p.operation_id=o.id AND "+target+"=?))")){s.setLong(1,id);s.setLong(2,id);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Производство ещё сохраняется. Дождитесь восстановления операции.");}}
    }
    public static int fisheryLevel(Connection db,long town)throws SQLException {
        try(var s=db.prepareStatement("SELECT fishery_level FROM town_production_settings WHERE town_id=?")){s.setLong(1,town);try(var r=s.executeQuery()){return r.next()?r.getInt(1):1;}}
    }
    public static void fisheryLevel(Connection db,long town,int level)throws SQLException {
        if(level<1 || level>4)throw new IllegalArgumentException("Некорректный уровень рыбной фермы.");
        requireTownUnlocked(db,town);
        try(var s=db.prepareStatement("INSERT INTO town_production_settings(town_id,fishery_level) VALUES(?,?) ON CONFLICT(town_id) DO UPDATE SET fishery_level=MAX(fishery_level,excluded.fishery_level)")){s.setLong(1,town);s.setInt(2,level);s.executeUpdate();}
    }
    public static int trommelLevel(Connection db,long town)throws SQLException {
        try(var s=db.prepareStatement("SELECT trommel_level FROM town_production_settings WHERE town_id=?")){s.setLong(1,town);try(var r=s.executeQuery()){return r.next()?r.getInt(1):1;}}
    }
    public static int quarryLevel(Connection db,long town)throws SQLException {
        try(var s=db.prepareStatement("SELECT quarry_level FROM town_production_settings WHERE town_id=?")){s.setLong(1,town);try(var r=s.executeQuery()){return r.next()?r.getInt(1):1;}}
    }
    public static void quarryLevel(Connection db,long town,int level)throws SQLException {
        if(level<1 || level>4)throw new IllegalArgumentException("Некорректный уровень карьера.");
        requireTownUnlocked(db,town);
        try(var s=db.prepareStatement("INSERT INTO town_production_settings(town_id,quarry_level) VALUES(?,?) ON CONFLICT(town_id) DO UPDATE SET quarry_level=MAX(quarry_level,excluded.quarry_level)")){s.setLong(1,town);s.setInt(2,level);s.executeUpdate();}
    }
    public static void trommelLevel(Connection db,long town,int level)throws SQLException {
        if(level<1 || level>4)throw new IllegalArgumentException("Некорректный уровень дробилки.");
        requireTownUnlocked(db,town);
        try(var s=db.prepareStatement("INSERT INTO town_production_settings(town_id,trommel_level) VALUES(?,?) ON CONFLICT(town_id) DO UPDATE SET trommel_level=MAX(trommel_level,excluded.trommel_level)")){s.setLong(1,town);s.setInt(2,level);s.executeUpdate();}
    }
}
