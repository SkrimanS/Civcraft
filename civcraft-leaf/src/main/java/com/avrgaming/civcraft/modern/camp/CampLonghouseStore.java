package com.avrgaming.civcraft.modern.camp;

import java.sql.*;
import java.util.*;
import com.avrgaming.civcraft.modern.trade.GoodieChestStore;

/** Write-ahead camp chest receipt. Sifting and longhouse meals share exclusion/recovery. */
public final class CampLonghouseStore {
    private CampLonghouseStore() {}
    public record Pending(String id,long camp,UUID leader,int level,int count,double coins,String result,byte[] containers,String fireOperation) {
        public Pending(String id,long camp,UUID leader,int level,int count,double coins,String result,byte[] containers){this(id,camp,leader,level,count,coins,result,containers,null);}
    }
    public static void install(Connection db,int maximum)throws SQLException {
        if(maximum<1)throw new IllegalArgumentException("Invalid longhouse maximum");
        try(var s=db.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS camp_longhouse_operations(
                  id TEXT PRIMARY KEY,camp_id INTEGER NOT NULL,leader_uuid TEXT NOT NULL,
                  level INTEGER NOT NULL,count INTEGER NOT NULL,coins REAL NOT NULL,result TEXT NOT NULL,
                  containers BLOB,status TEXT NOT NULL DEFAULT 'PREPARED',created_at INTEGER NOT NULL)
                """);
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_pending_camp_meal ON camp_longhouse_operations(camp_id) WHERE status='PREPARED'");
            boolean linked=false;try(var r=s.executeQuery("PRAGMA table_info(camp_longhouse_operations)")){while(r.next())if(r.getString("name").equals("fire_operation"))linked=true;}
            if(!linked)s.executeUpdate("ALTER TABLE camp_longhouse_operations ADD COLUMN fire_operation TEXT");
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_meal_per_camp_fire ON camp_longhouse_operations(fire_operation) WHERE fire_operation IS NOT NULL");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS camp_longhouse_migrations(id INTEGER PRIMARY KEY)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS camp_longhouse_legacy_progress(camp_id INTEGER PRIMARY KEY,level INTEGER NOT NULL,experience INTEGER NOT NULL)");
            // The former XP model cannot be interpreted as hourly consumption counts.
            if(s.executeUpdate("INSERT OR IGNORE INTO camp_longhouse_migrations(id) VALUES(1)")==1) {
                s.executeUpdate("INSERT INTO camp_longhouse_legacy_progress SELECT id,level,experience FROM camps");
                s.executeUpdate("UPDATE camps SET level=MIN("+maximum+",MAX(1,level)),experience=0");
            }
            s.executeUpdate("CREATE TABLE IF NOT EXISTS camp_npc_progress(camp_id INTEGER PRIMARY KEY REFERENCES camps(id) ON DELETE CASCADE,experience INTEGER NOT NULL DEFAULT 0 CHECK(experience>=0))");
            s.executeUpdate("INSERT OR IGNORE INTO camp_npc_progress SELECT p.camp_id,MAX(0,p.experience) FROM camp_longhouse_legacy_progress p JOIN camps c ON c.id=p.camp_id");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS camp_inventory_blocks_delete BEFORE DELETE ON camps WHEN EXISTS(SELECT 1 FROM camp_longhouse_operations WHERE camp_id=OLD.id AND status='PREPARED') BEGIN SELECT RAISE(ABORT,'Camp inventory is pending'); END");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS camp_inventory_blocks_leader_change BEFORE UPDATE OF owner_uuid ON camps WHEN NEW.owner_uuid<>OLD.owner_uuid AND EXISTS(SELECT 1 FROM camp_longhouse_operations WHERE camp_id=OLD.id AND status='PREPARED') BEGIN SELECT RAISE(ABORT,'Camp inventory is pending'); END");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS camp_inventory_blocks_progress_change BEFORE UPDATE OF level,experience ON camps WHEN (NEW.level<>OLD.level OR NEW.experience<>OLD.experience) AND EXISTS(SELECT 1 FROM camp_longhouse_operations WHERE camp_id=OLD.id AND status='PREPARED') BEGIN SELECT RAISE(ABORT,'Camp inventory is pending'); END");
        }
    }
    public static void requireUnlocked(Connection db,long camp)throws SQLException {
        requireUnlocked(db,camp,false);
    }
    static void requireUnlocked(Connection db,long camp,boolean allowDueMeal)throws SQLException {
        boolean fire=false;
        for(String table:List.of("camp_longhouse_operations","camp_fire_operations")) {
            boolean exists;try(var s=db.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")){s.setString(1,table);try(var r=s.executeQuery()){exists=r.next();}}if(!exists)continue;
            if(table.equals("camp_fire_operations"))fire=true;
            try(var s=db.prepareStatement("SELECT 1 FROM "+table+" WHERE camp_id=? AND status='PREPARED'")) {
                s.setLong(1,camp);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Работа лагерного инвентаря ещё сохраняется. Дождитесь восстановления операции.");}
            }
        }
        if(fire && !allowDueMeal)try(var s=db.prepareStatement("SELECT 1 FROM camp_fire_operations WHERE camp_id=? AND status='COMPLETED' AND meal_due=1")) {
            s.setLong(1,camp);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Питание большого дома за прошедший час ещё восстанавливается.");}
        }
    }
    public static void prepare(Connection db,Pending meal,int beforeLevel,int beforeCount)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Camp production requires a transaction");
        requireUnlocked(db,meal.camp(),meal.fireOperation()!=null);
        GoodieChestStore.requireImagesUnlocked(db,meal.containers());
        boolean sifting=meal.result().equals("SIFT");
        if(meal.fireOperation()!=null)try(var s=db.prepareStatement("SELECT 1 FROM camp_fire_operations WHERE id=? AND camp_id=? AND status='COMPLETED' AND meal_due=1")) {
            s.setString(1,meal.fireOperation());s.setLong(2,meal.camp());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Camp meal has no completed fuel cycle");}
        }
        if(sifting && (meal.coins()!=0 || meal.level()!=beforeLevel || meal.count()!=beforeCount))throw new SQLException("Sifter cannot change camp progression");
        try(var s=db.prepareStatement("SELECT owner_uuid,level,experience,"+(sifting?"breaker_upgrade":"longhouse_upgrade")+" FROM camps WHERE id=?")) {
            s.setLong(1,meal.camp());try(var r=s.executeQuery()) {
                if(!r.next() || !meal.leader().toString().equals(r.getString(1)) || r.getInt(2)!=beforeLevel || r.getInt(3)!=beforeCount || r.getInt(4)==0)
                    throw new SQLException("Camp changed before its meal");
            }
        }
        try(var s=db.prepareStatement("INSERT INTO camp_longhouse_operations(id,camp_id,leader_uuid,level,count,coins,result,containers,created_at,fire_operation) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            s.setString(1,meal.id());s.setLong(2,meal.camp());s.setString(3,meal.leader().toString());s.setInt(4,meal.level());s.setInt(5,meal.count());
            s.setDouble(6,meal.coins());s.setString(7,meal.result());s.setBytes(8,meal.containers());s.setLong(9,System.currentTimeMillis());s.setString(10,meal.fireOperation());s.executeUpdate();
        }
    }
    public static List<Pending> pending(Connection db)throws SQLException {
        var out=new ArrayList<Pending>();
        try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM camp_longhouse_operations WHERE status='PREPARED' ORDER BY created_at,id")) {
            while(r.next())out.add(new Pending(r.getString("id"),r.getLong("camp_id"),UUID.fromString(r.getString("leader_uuid")),r.getInt("level"),r.getInt("count"),r.getDouble("coins"),r.getString("result"),r.getBytes("containers"),r.getString("fire_operation")));
        }return List.copyOf(out);
    }
    /** Caller owns transaction. Updating the receipt and issuing money are inseparable. */
    public static boolean complete(Connection db,Pending meal)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Camp production requires a transaction");
        try(var s=db.prepareStatement("UPDATE camp_longhouse_operations SET status='COMPLETED',containers=NULL WHERE id=? AND status='PREPARED'")) {
            s.setString(1,meal.id());if(s.executeUpdate()==0)return false;
        }
        if(meal.result().equals("SIFT")) {
            // No monetary audit needs to retain 4,320 empty sifter rows per camp per day.
            try(var s=db.prepareStatement("DELETE FROM camp_longhouse_operations WHERE id=? AND status='COMPLETED'")){s.setString(1,meal.id());s.executeUpdate();}
            return true;
        }
        try(var s=db.prepareStatement("UPDATE camps SET level=?,experience=? WHERE id=? AND owner_uuid=?")) {
            s.setInt(1,meal.level());s.setInt(2,meal.count());s.setLong(3,meal.camp());s.setString(4,meal.leader().toString());
            if(s.executeUpdate()!=1)throw new SQLException("Camp disappeared during meal recovery");
        }
        if(meal.coins()>0)try(var s=db.prepareStatement("UPDATE residents SET coins=coins+?,updated_at=? WHERE uuid=?")) {
            s.setDouble(1,meal.coins());s.setLong(2,System.currentTimeMillis());s.setString(3,meal.leader().toString());
            if(s.executeUpdate()!=1)throw new SQLException("Longhouse leader is missing");
        }
        if(meal.fireOperation()!=null)try(var s=db.prepareStatement("UPDATE camp_fire_operations SET meal_due=0 WHERE id=? AND meal_due=1 AND status='COMPLETED'")) {
            s.setString(1,meal.fireOperation());if(s.executeUpdate()!=1)throw new SQLException("Camp fuel cycle was already settled");
        }
        return true;
    }
}
