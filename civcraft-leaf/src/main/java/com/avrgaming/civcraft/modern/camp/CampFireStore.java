package com.avrgaming.civcraft.modern.camp;

import java.sql.*;
import java.util.*;
import com.avrgaming.civcraft.modern.trade.GoodieChestStore;

/** Coal image and firepoint transition share one durable intent. Caller owns transactions. */
public final class CampFireStore {
    private CampFireStore() {}
    public record Pending(String id,long camp,UUID leader,int before,int after,boolean fed,byte[] containers) {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS camp_fire_operations(id TEXT PRIMARY KEY,camp_id INTEGER NOT NULL,leader_uuid TEXT NOT NULL,before_points INTEGER NOT NULL,after_points INTEGER NOT NULL,fed INTEGER NOT NULL,containers BLOB,status TEXT NOT NULL DEFAULT 'PREPARED',created_at INTEGER NOT NULL)");
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_pending_camp_fire ON camp_fire_operations(camp_id) WHERE status='PREPARED'");
            boolean due=false;try(var r=s.executeQuery("PRAGMA table_info(camp_fire_operations)")){while(r.next())if(r.getString("name").equals("meal_due"))due=true;}
            if(!due)s.executeUpdate("ALTER TABLE camp_fire_operations ADD COLUMN meal_due INTEGER NOT NULL DEFAULT 0");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS camp_fire_blocks_delete BEFORE DELETE ON camps WHEN EXISTS(SELECT 1 FROM camp_fire_operations WHERE camp_id=OLD.id AND status='PREPARED') BEGIN SELECT RAISE(ABORT,'Camp fire is pending'); END");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS camp_fire_blocks_owner_change BEFORE UPDATE OF owner_uuid ON camps WHEN NEW.owner_uuid<>OLD.owner_uuid AND EXISTS(SELECT 1 FROM camp_fire_operations WHERE camp_id=OLD.id AND status='PREPARED') BEGIN SELECT RAISE(ABORT,'Camp fire is pending'); END");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS camp_fire_blocks_points_change BEFORE UPDATE OF firepoints_hours ON camps WHEN NEW.firepoints_hours<>OLD.firepoints_hours AND EXISTS(SELECT 1 FROM camp_fire_operations WHERE camp_id=OLD.id AND status='PREPARED') BEGIN SELECT RAISE(ABORT,'Camp fire is pending'); END");
        }
    }
    public static void prepare(Connection db,Pending p)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Camp fire requires a transaction");
        CampLonghouseStore.requireUnlocked(db,p.camp());GoodieChestStore.requireImagesUnlocked(db,p.containers());
        boolean meal;
        try(var s=db.prepareStatement("SELECT owner_uuid,firepoints_hours,longhouse_upgrade FROM camps WHERE id=?")) {
            s.setLong(1,p.camp());try(var r=s.executeQuery()){if(!r.next() || !p.leader().toString().equals(r.getString(1)) || p.before()!=r.getInt(2))throw new SQLException("Camp changed before fuel consumption");meal=p.after()>=0 && r.getInt(3)!=0;}
        }
        try(var s=db.prepareStatement("INSERT INTO camp_fire_operations(id,camp_id,leader_uuid,before_points,after_points,fed,containers,created_at,meal_due) VALUES(?,?,?,?,?,?,?,?,?)")) {
            s.setString(1,p.id());s.setLong(2,p.camp());s.setString(3,p.leader().toString());s.setInt(4,p.before());s.setInt(5,p.after());s.setInt(6,p.fed()?1:0);s.setBytes(7,p.containers());s.setLong(8,System.currentTimeMillis());s.setInt(9,meal?1:0);s.executeUpdate();
        }
    }
    public static List<Pending> pending(Connection db)throws SQLException {
        var out=new ArrayList<Pending>();try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM camp_fire_operations WHERE status='PREPARED' ORDER BY created_at,id")){while(r.next())out.add(new Pending(r.getString("id"),r.getLong("camp_id"),UUID.fromString(r.getString("leader_uuid")),r.getInt("before_points"),r.getInt("after_points"),r.getInt("fed")!=0,r.getBytes("containers")));}return List.copyOf(out);
    }
    public static boolean complete(Connection db,Pending p)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Camp fire requires a transaction");
        try(var s=db.prepareStatement("UPDATE camp_fire_operations SET status='COMPLETED',containers=NULL WHERE id=? AND status='PREPARED'")){s.setString(1,p.id());if(s.executeUpdate()==0)return false;}
        try(var s=db.prepareStatement("UPDATE camps SET firepoints_hours=? WHERE id=? AND owner_uuid=? AND firepoints_hours=?")) {
            s.setInt(1,p.after());s.setLong(2,p.camp());s.setString(3,p.leader().toString());s.setInt(4,p.before());if(s.executeUpdate()!=1)throw new SQLException("Camp changed during fire recovery");
        }return true;
    }
}
