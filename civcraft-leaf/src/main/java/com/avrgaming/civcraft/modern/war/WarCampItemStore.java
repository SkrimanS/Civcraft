package com.avrgaming.civcraft.modern.war;
import java.sql.*;
/** Player inventory and camp preparation share one durable intent. */
public final class WarCampItemStore {
    private WarCampItemStore() {}
    public record Receipt(String id,long camp,String player,byte[] inventory,String state) {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS war_camp_item_receipts(id TEXT PRIMARY KEY,camp_id INTEGER NOT NULL UNIQUE REFERENCES war_camps(id),player_uuid TEXT NOT NULL,inventory BLOB NOT NULL,state TEXT NOT NULL CHECK(state IN ('PREPARED','DONE')),created_at INTEGER NOT NULL)");
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_pending_camp_item ON war_camp_item_receipts(player_uuid) WHERE state='PREPARED'");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS war_camp_item_checkpoints(player_uuid TEXT PRIMARY KEY REFERENCES residents(uuid) ON DELETE CASCADE,receipt_id TEXT NOT NULL,inventory BLOB NOT NULL)");
        }
    }
    public static Receipt prepare(Connection db,String id,long camp,String player,byte[] after,long now)throws SQLException {
        try(var s=db.prepareStatement("SELECT * FROM war_camp_item_receipts WHERE id=?")){s.setString(1,id);try(var r=s.executeQuery()){if(r.next())return new Receipt(id,r.getLong("camp_id"),r.getString("player_uuid"),r.getBytes("inventory"),r.getString("state"));}}
        if(!WarCampStore.camp(db,camp).state().equals("PREPARED"))throw new IllegalArgumentException("Camp no longer awaits item consumption");
        try(var s=db.prepareStatement("INSERT INTO war_camp_item_receipts VALUES(?,?,?,?,'PREPARED',?)")){s.setString(1,id);s.setLong(2,camp);s.setString(3,player);s.setBytes(4,after);s.setLong(5,now);s.executeUpdate();}return new Receipt(id,camp,player,after.clone(),"PREPARED");
    }
    public static Receipt pending(Connection db,String player)throws SQLException {
        try(var s=db.prepareStatement("SELECT * FROM war_camp_item_receipts WHERE player_uuid=? AND state='PREPARED'")){s.setString(1,player);try(var r=s.executeQuery()){return r.next()?new Receipt(r.getString("id"),r.getLong("camp_id"),player,r.getBytes("inventory"),"PREPARED"):null;}}
    }
    public static Receipt checkpoint(Connection db,String player)throws SQLException {
        try(var s=db.prepareStatement("SELECT receipt_id,inventory FROM war_camp_item_checkpoints WHERE player_uuid=?")){s.setString(1,player);try(var r=s.executeQuery()){return r.next()?new Receipt(r.getString(1),0,player,r.getBytes(2),"DONE"):null;}}
    }
    public static void finish(Connection db,Receipt receipt)throws SQLException {
        try(var s=db.prepareStatement("UPDATE war_camp_item_receipts SET state='DONE' WHERE id=? AND state='PREPARED'")){s.setString(1,receipt.id());if(s.executeUpdate()!=1)return;}
        try(var s=db.prepareStatement("INSERT INTO war_camp_item_checkpoints VALUES(?,?,?) ON CONFLICT(player_uuid) DO UPDATE SET receipt_id=excluded.receipt_id,inventory=excluded.inventory")){s.setString(1,receipt.player());s.setString(2,receipt.id());s.setBytes(3,receipt.inventory());s.executeUpdate();}
    }
}
