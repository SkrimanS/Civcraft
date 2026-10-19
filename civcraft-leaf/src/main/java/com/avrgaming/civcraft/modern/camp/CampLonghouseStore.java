package com.avrgaming.civcraft.modern.camp;

import java.sql.*;
import java.util.*;
import com.avrgaming.civcraft.modern.trade.GoodieChestStore;

/** Write-ahead meal receipt: physical food/token images precede the monetary reward. */
public final class CampLonghouseStore {
    private CampLonghouseStore() {}
    public record Pending(String id,long camp,UUID leader,int level,int count,double coins,String result,byte[] containers) {}
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
            s.executeUpdate("CREATE TABLE IF NOT EXISTS camp_longhouse_migrations(id INTEGER PRIMARY KEY)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS camp_longhouse_legacy_progress(camp_id INTEGER PRIMARY KEY,level INTEGER NOT NULL,experience INTEGER NOT NULL)");
            // The former XP model cannot be interpreted as hourly consumption counts.
            if(s.executeUpdate("INSERT OR IGNORE INTO camp_longhouse_migrations(id) VALUES(1)")==1) {
                s.executeUpdate("INSERT INTO camp_longhouse_legacy_progress SELECT id,level,experience FROM camps");
                s.executeUpdate("UPDATE camps SET level=MIN("+maximum+",MAX(1,level)),experience=0");
            }
        }
    }
    public static void requireUnlocked(Connection db,long camp)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='camp_longhouse_operations'");var r=s.executeQuery()){if(!r.next())return;}
        try(var s=db.prepareStatement("SELECT 1 FROM camp_longhouse_operations WHERE camp_id=? AND status='PREPARED'")) {
            s.setLong(1,camp);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Питание большого дома ещё сохраняется. Дождитесь восстановления операции.");}
        }
    }
    public static void prepare(Connection db,Pending meal,int beforeLevel,int beforeCount)throws SQLException {
        requireUnlocked(db,meal.camp());
        GoodieChestStore.requireImagesUnlocked(db,meal.containers());
        try(var s=db.prepareStatement("SELECT owner_uuid,level,experience,longhouse_upgrade FROM camps WHERE id=?")) {
            s.setLong(1,meal.camp());try(var r=s.executeQuery()) {
                if(!r.next() || !meal.leader().toString().equals(r.getString(1)) || r.getInt(2)!=beforeLevel || r.getInt(3)!=beforeCount || r.getInt(4)==0)
                    throw new SQLException("Camp changed before its meal");
            }
        }
        try(var s=db.prepareStatement("INSERT INTO camp_longhouse_operations(id,camp_id,leader_uuid,level,count,coins,result,containers,created_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
            s.setString(1,meal.id());s.setLong(2,meal.camp());s.setString(3,meal.leader().toString());s.setInt(4,meal.level());s.setInt(5,meal.count());
            s.setDouble(6,meal.coins());s.setString(7,meal.result());s.setBytes(8,meal.containers());s.setLong(9,System.currentTimeMillis());s.executeUpdate();
        }
    }
    public static List<Pending> pending(Connection db)throws SQLException {
        var out=new ArrayList<Pending>();
        try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM camp_longhouse_operations WHERE status='PREPARED' ORDER BY created_at,id")) {
            while(r.next())out.add(new Pending(r.getString("id"),r.getLong("camp_id"),UUID.fromString(r.getString("leader_uuid")),r.getInt("level"),r.getInt("count"),r.getDouble("coins"),r.getString("result"),r.getBytes("containers")));
        }return List.copyOf(out);
    }
    /** Caller owns transaction. Updating the receipt and issuing money are inseparable. */
    public static boolean complete(Connection db,Pending meal)throws SQLException {
        try(var s=db.prepareStatement("UPDATE camp_longhouse_operations SET status='COMPLETED',containers=NULL WHERE id=? AND status='PREPARED'")) {
            s.setString(1,meal.id());if(s.executeUpdate()==0)return false;
        }
        try(var s=db.prepareStatement("UPDATE camps SET level=?,experience=? WHERE id=? AND owner_uuid=?")) {
            s.setInt(1,meal.level());s.setInt(2,meal.count());s.setLong(3,meal.camp());s.setString(4,meal.leader().toString());
            if(s.executeUpdate()!=1)throw new SQLException("Camp disappeared during meal recovery");
        }
        if(meal.coins()>0)try(var s=db.prepareStatement("UPDATE residents SET coins=coins+?,updated_at=? WHERE uuid=?")) {
            s.setDouble(1,meal.coins());s.setLong(2,System.currentTimeMillis());s.setString(3,meal.leader().toString());
            if(s.executeUpdate()!=1)throw new SQLException("Longhouse leader is missing");
        }
        return true;
    }
}
