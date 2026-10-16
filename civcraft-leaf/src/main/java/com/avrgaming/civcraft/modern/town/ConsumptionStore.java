package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import java.util.*;

/** Callers hold a transaction. Complete only after container changes have been flushed to disk. */
public final class ConsumptionStore {
    private ConsumptionStore() {}
    public record State(long build,int level,int count,String result,long period,long hour) {}
    public record Pending(String id,State before,ConsumptionRules.State after,long hour,
                          long town,long civ,long townCoins,long civCoins,long culture,boolean poisoned,byte[] containers) {
        public Pending {containers=containers.clone();}
        @Override public byte[] containers(){return containers.clone();}
    }
    public static void install(Connection db)throws SQLException {
        GranaryPoisonStore.install(db);
        try(var s=db.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS building_consumption(
                build_id INTEGER PRIMARY KEY REFERENCES structure_builds(id) ON DELETE CASCADE,
                level INTEGER NOT NULL CHECK(level>=1),count INTEGER NOT NULL CHECK(count>=0),
                result TEXT NOT NULL,period INTEGER NOT NULL CHECK(period>=1000),last_hour INTEGER NOT NULL)
                """);
            // Retain receipts independently of build deletion: an inventory intent must
            // never disappear while the world still needs recovery.
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS consumption_receipts(
                id TEXT PRIMARY KEY,build_id INTEGER NOT NULL,town_id INTEGER NOT NULL,civ_id INTEGER NOT NULL,
                period INTEGER NOT NULL,hour INTEGER NOT NULL,before_hour INTEGER NOT NULL,
                before_level INTEGER NOT NULL,before_count INTEGER NOT NULL,before_result TEXT NOT NULL,
                after_level INTEGER NOT NULL,after_count INTEGER NOT NULL,after_result TEXT NOT NULL,
                consumed INTEGER NOT NULL,town_coins INTEGER NOT NULL,civ_coins INTEGER NOT NULL,
                culture INTEGER NOT NULL,containers BLOB NOT NULL,status TEXT NOT NULL DEFAULT 'PREPARED',
                UNIQUE(build_id,period,hour))
                """);
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_pending_consumption ON consumption_receipts(build_id) WHERE status='PREPARED'");
            boolean poisoned=false;try(var r=s.executeQuery("PRAGMA table_info(consumption_receipts)")){while(r.next())if(r.getString("name").equals("poisoned"))poisoned=true;}
            if(!poisoned)s.executeUpdate("ALTER TABLE consumption_receipts ADD COLUMN poisoned INTEGER NOT NULL DEFAULT 0");
        }
    }
    public static State state(Connection db,long build)throws SQLException {
        try(var s=db.prepareStatement("SELECT * FROM building_consumption WHERE build_id=?")) {
            s.setLong(1,build);try(var r=s.executeQuery()) {
                return r.next()?new State(build,r.getInt("level"),r.getInt("count"),r.getString("result"),r.getLong("period"),r.getLong("last_hour")):null;
            }
        }
    }
    /** First discovery skips prior hours. Existing progress is not reset on restart. */
    public static State initialize(Connection db,long build,long period,long hour)throws SQLException {
        if(period<1000 || hour<0)throw new IllegalArgumentException("Invalid consumption clock");
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO building_consumption VALUES(?,1,0,'UNKNOWN',?,?)")) {
            s.setLong(1,build);s.setLong(2,period);s.setLong(3,hour);s.executeUpdate();
        }
        return state(db,build);
    }
    /** Skip offline hours, but recover existing receipts before advancing the clock. */
    public static void start(Connection db,long period,long hour)throws SQLException {
        if(period<1000 || hour<0)throw new IllegalArgumentException("Invalid consumption clock");
        if(!pending(db).isEmpty())throw new SQLException("Consumption recovery must finish before clock restart");
        try(var s=db.prepareStatement("UPDATE building_consumption SET period=?,last_hour=CASE WHEN period!=? THEN ? ELSE MAX(last_hour,?) END")) {
            s.setLong(1,period);s.setLong(2,period);s.setLong(3,hour);s.setLong(4,hour);s.executeUpdate();
        }
    }
    public static Pending prepare(Connection db,String id,State before,ConsumptionRules.State after,long hour,
                                  long townCoins,long civCoins,long culture,byte[] containers)throws SQLException {
        return prepare(db,id,before,after,hour,townCoins,civCoins,culture,false,containers);
    }
    public static Pending prepare(Connection db,String id,State before,ConsumptionRules.State after,long hour,
                                  long townCoins,long civCoins,long culture,boolean poisoned,byte[] containers)throws SQLException {
        if(id==null || id.isBlank() || before.period()<1000 || hour<=before.hour() || after.level()<1 || after.count()<0
                || townCoins<0 || civCoins<0 || culture<0 || containers==null || containers.length==0
                || (!after.consumed() && (townCoins!=0 || civCoins!=0 || culture!=0)))
            throw new IllegalArgumentException("Invalid consumption intent");
        if(poisoned && (!after.consumed() || townCoins!=0 || civCoins!=0 || culture!=0))
            throw new IllegalArgumentException("Poisoned meals must consume food and cannot earn production");
        if(!before.equals(state(db,before.build())))throw new SQLException("Consumption state changed before prepare");
        long town,civ;
        try(var s=db.prepareStatement("SELECT t.id,t.civ_id FROM active_structure_builds b JOIN towns t ON t.id=b.town_id WHERE b.id=? AND b.status='COMPLETE'")) {
            s.setLong(1,before.build());try(var r=s.executeQuery()) {
                if(!r.next())throw new SQLException("Consuming building is not complete or has no town");
                town=r.getLong(1);civ=r.getLong(2);
            }
        }
        try(var s=db.prepareStatement("""
            INSERT INTO consumption_receipts(id,build_id,town_id,civ_id,period,hour,before_hour,before_level,
            before_count,before_result,after_level,after_count,after_result,consumed,town_coins,civ_coins,culture,containers,poisoned)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            s.setString(1,id);s.setLong(2,before.build());s.setLong(3,town);s.setLong(4,civ);s.setLong(5,before.period());s.setLong(6,hour);
            s.setLong(7,before.hour());s.setInt(8,before.level());s.setInt(9,before.count());s.setString(10,before.result());
            s.setInt(11,after.level());s.setInt(12,after.count());s.setString(13,after.result().name());s.setBoolean(14,after.consumed());
            s.setLong(15,townCoins);s.setLong(16,civCoins);s.setLong(17,culture);s.setBytes(18,containers);s.setBoolean(19,poisoned);s.executeUpdate();
        }
        if(poisoned)GranaryPoisonStore.reserve(db,town);
        return new Pending(id,before,after,hour,town,civ,townCoins,civCoins,culture,poisoned,containers);
    }
    public static List<Pending> pending(Connection db)throws SQLException {
        var result=new ArrayList<Pending>();
        try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM consumption_receipts WHERE status='PREPARED' ORDER BY period,hour,build_id")) {
            while(r.next())result.add(read(r));
        }
        return List.copyOf(result);
    }
    /** Exactly-once state and payouts. Failures roll back through StorageBootstrap.transaction. */
    public static boolean complete(Connection db,String id)throws SQLException {
        Pending pending;
        try(var s=db.prepareStatement("SELECT * FROM consumption_receipts WHERE id=?")) {
            s.setString(1,id);try(var r=s.executeQuery()) {
                if(!r.next())throw new SQLException("Unknown consumption receipt");
                if(r.getString("status").equals("COMPLETE"))return false;
                pending=read(r);
            }
        }
        if(!pending.before().equals(state(db,pending.before().build())))throw new SQLException("Consumption state changed during recovery");
        try(var s=db.prepareStatement("UPDATE building_consumption SET level=?,count=?,result=?,last_hour=? WHERE build_id=?")) {
            s.setInt(1,pending.after().level());s.setInt(2,pending.after().count());s.setString(3,pending.after().result().name());
            s.setLong(4,pending.hour());s.setLong(5,pending.before().build());
            if(s.executeUpdate()!=1)throw new SQLException("Consumption building no longer exists");
        }
        if(pending.townCoins()>0)UpkeepStore.deposit(db,"towns",pending.town(),pending.townCoins());
        if(pending.civCoins()>0)UpkeepStore.deposit(db,"civilizations",pending.civ(),pending.civCoins());
        if(pending.culture()>0)try(var s=db.prepareStatement("UPDATE towns SET accumulated_culture=accumulated_culture+? WHERE id=? AND accumulated_culture<=?")) {
            s.setLong(1,pending.culture());s.setLong(2,pending.town());s.setLong(3,Long.MAX_VALUE-pending.culture());
            if(s.executeUpdate()!=1)throw new SQLException("Consumption culture exceeds supported balance");
        }
        try(var s=db.prepareStatement("UPDATE consumption_receipts SET status='COMPLETE',containers=X'' WHERE id=? AND status='PREPARED'")) {
            s.setString(1,id);if(s.executeUpdate()!=1)throw new SQLException("Consumption receipt changed during completion");
        }
        return true;
    }
    private static Pending read(ResultSet r)throws SQLException {
        var before=new State(r.getLong("build_id"),r.getInt("before_level"),r.getInt("before_count"),r.getString("before_result"),r.getLong("period"),r.getLong("before_hour"));
        var after=new ConsumptionRules.State(r.getInt("after_level"),r.getInt("after_count"),ConsumptionRules.Result.valueOf(r.getString("after_result")),r.getBoolean("consumed"));
        return new Pending(r.getString("id"),before,after,r.getLong("hour"),r.getLong("town_id"),r.getLong("civ_id"),r.getLong("town_coins"),r.getLong("civ_coins"),r.getLong("culture"),r.getBoolean("poisoned"),r.getBytes("containers"));
    }
}
