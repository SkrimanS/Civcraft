package com.avrgaming.civcraft.modern.foundation;

import java.sql.*;
import java.util.*;

/** Durable item reservation. CREATING is deliberately retained until creation is reconciled. */
public final class FoundationEscrowStore {
    private FoundationEscrowStore() {}
    public static String stateName(String state){return switch(state){case "RESERVING"->"сохраняется резерв";case "RESERVED"->"предмет зарезервирован";case "CREATING"->"создание требует сверки";case "COMMITTED"->"основание завершено";case "CANCEL_REQUESTED"->"ожидается возврат";case "REFUNDING"->"сохраняется возврат";case "REFUNDED"->"предмет возвращён";default->"неизвестное состояние";};}
    public record Escrow(String id,UUID player,String kind,String state,byte[] item,String reserve,String refund) {
        public Escrow{item=item.clone();}
        @Override public byte[] item(){return item.clone();}
    }
    private static void transaction(Connection db)throws SQLException{if(db.getAutoCommit())throw new SQLException("Foundation escrow requires a transaction");}
    public static boolean installed(Connection db)throws SQLException {
        try(var s=db.createStatement();var r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='foundation_escrows'")){return r.next();}
    }
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS foundation_escrows(id TEXT PRIMARY KEY,player_uuid TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN ('CAMP','CIVILIZATION','TOWN')),state TEXT NOT NULL CHECK(state IN ('RESERVING','RESERVED','CREATING','COMMITTED','CANCEL_REQUESTED','REFUNDING','REFUNDED')),item BLOB NOT NULL,reserve_transfer TEXT NOT NULL REFERENCES goodie_chest_transfers(id),refund_transfer TEXT REFERENCES goodie_chest_transfers(id),created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_open_foundation_escrow ON foundation_escrows(player_uuid) WHERE state NOT IN ('COMMITTED','REFUNDED')");
        }
        FoundationCreationStore.install(db);
    }
    private static void requireTransfer(Connection db,String transfer,UUID player)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM goodie_chest_transfers WHERE id=? AND player_uuid=? AND status='PREPARED'")){s.setString(1,transfer);s.setString(2,player.toString());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Foundation escrow requires its player's prepared inventory transfer");}}
    }
    public static void reserve(Connection db,String id,UUID player,String kind,byte[] item,String transfer,long now)throws SQLException {
        transaction(db);if(id==null || id.isBlank() || player==null || item==null || item.length<1 || item.length>16*1024*1024)throw new IllegalArgumentException("Неверный резерв предмета основания.");
        requireTransfer(db,transfer,player);
        if(activePlayer(db,player))throw new IllegalArgumentException("Сначала завершите восстановление прежнего основания.");
        try(var s=db.prepareStatement("INSERT INTO foundation_escrows VALUES(?,?,?,'RESERVING',?,?,NULL,?,?)")) {
            s.setString(1,id);s.setString(2,player.toString());s.setString(3,kind);s.setBytes(4,item);s.setString(5,transfer);s.setLong(6,now);s.setLong(7,now);s.executeUpdate();
        }
    }
    public static boolean activePlayer(Connection db,UUID player)throws SQLException {
        if(!installed(db))return false;
        try(var s=db.prepareStatement("SELECT 1 FROM foundation_escrows WHERE player_uuid=? AND state NOT IN ('COMMITTED','REFUNDED')")){s.setString(1,player.toString());try(var r=s.executeQuery()){return r.next();}}
    }
    private static Escrow row(ResultSet r)throws SQLException{return new Escrow(r.getString("id"),UUID.fromString(r.getString("player_uuid")),r.getString("kind"),r.getString("state"),r.getBytes("item"),r.getString("reserve_transfer"),r.getString("refund_transfer"));}
    public static Escrow find(Connection db,String id)throws SQLException {
        try(var s=db.prepareStatement("SELECT * FROM foundation_escrows WHERE id=?")){s.setString(1,id);try(var r=s.executeQuery()){return r.next()?row(r):null;}}
    }
    public static List<Escrow> active(Connection db)throws SQLException {
        if(!installed(db))return List.of();
        var out=new ArrayList<Escrow>();try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM foundation_escrows WHERE state NOT IN ('COMMITTED','REFUNDED') ORDER BY created_at,id")){while(r.next())out.add(row(r));}return out;
    }
    public static boolean reservationSaved(Connection db,String id)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM foundation_escrows e JOIN goodie_chest_transfers t ON t.id=e.reserve_transfer WHERE e.id=? AND t.status='COMPLETE'")){s.setString(1,id);try(var r=s.executeQuery()){return r.next();}}
    }
    public static void beginCreation(Connection db,String id,FoundationCreationStore.Draft draft)throws SQLException {
        transaction(db);FoundationCreationStore.prepare(db,id,draft);beginCreation(db,id);
    }
    public static void beginCreation(Connection db,String id)throws SQLException {
        transaction(db);
        if(!reservationSaved(db,id))throw new IllegalArgumentException("Предмет основания ещё сохраняется. Повторите подтверждение позже.");
        try(var s=db.prepareStatement("UPDATE foundation_escrows SET state='CREATING',updated_at=? WHERE id=? AND state='RESERVED'")){s.setLong(1,System.currentTimeMillis());s.setString(2,id);if(s.executeUpdate()!=1)throw new IllegalArgumentException("Резерв основания уже обрабатывается.");}
    }
    public static void committed(Connection db,String id)throws SQLException {
        transaction(db);try(var s=db.prepareStatement("UPDATE foundation_escrows SET state='COMMITTED',item=X'',updated_at=? WHERE id=? AND state='CREATING'")){s.setLong(1,System.currentTimeMillis());s.setString(2,id);if(s.executeUpdate()!=1)throw new SQLException("Foundation escrow is not creating");}
    }
    public static void cancel(Connection db,String id,boolean creationRolledBack)throws SQLException {
        if(creationRolledBack && FoundationCreationStore.find(db,id)!=null && !FoundationPaymentStore.cancellationReady(db,id))throw new SQLException("Foundation money must be reconciled before item refund");
        transaction(db);if(creationRolledBack && FoundationCreationStore.hasCreatedRecords(db,id) && !FoundationCreationRollback.completed(db,id))throw new SQLException("Created foundation assets must be reconciled before refund");String allowed=creationRolledBack?"'RESERVING','RESERVED','CREATING'":"'RESERVING','RESERVED'";
        try(var s=db.prepareStatement("UPDATE foundation_escrows SET state='CANCEL_REQUESTED',updated_at=? WHERE id=? AND state IN ("+allowed+")")){s.setLong(1,System.currentTimeMillis());s.setString(2,id);s.executeUpdate();}
    }
    public static void refund(Connection db,String id,UUID player,String transfer)throws SQLException {
        transaction(db);if(!reservationSaved(db,id))throw new IllegalArgumentException("Списание предмета основания ещё не сохранено.");
        requireTransfer(db,transfer,player);
        try(var s=db.prepareStatement("UPDATE foundation_escrows SET state='REFUNDING',refund_transfer=?,updated_at=? WHERE id=? AND player_uuid=? AND state='CANCEL_REQUESTED'")){s.setString(1,transfer);s.setLong(2,System.currentTimeMillis());s.setString(3,id);s.setString(4,player.toString());if(s.executeUpdate()!=1)throw new IllegalArgumentException("Возврат основания уже обрабатывается.");}
    }
    public static void transferComplete(Connection db,String transfer)throws SQLException {
        transaction(db);if(!installed(db))return;
        try(var s=db.prepareStatement("UPDATE foundation_escrows SET state='RESERVED',updated_at=? WHERE reserve_transfer=? AND state='RESERVING'")){s.setLong(1,System.currentTimeMillis());s.setString(2,transfer);s.executeUpdate();}
        try(var s=db.prepareStatement("UPDATE foundation_escrows SET state='REFUNDED',item=X'',updated_at=? WHERE refund_transfer=? AND state='REFUNDING'")){s.setLong(1,System.currentTimeMillis());s.setString(2,transfer);s.executeUpdate();}
    }
}
