package com.avrgaming.civcraft.modern.foundation;

import java.sql.*;
import java.util.*;

/** Local money is transactional; Vault dispatch and its acknowledgement are separate. */
public final class FoundationPaymentStore {
    private FoundationPaymentStore() {}
    public record Receipt(String id,UUID player,long amount,String mode,String provider,String currency,String state,String error) {}
    private static void transaction(Connection db)throws SQLException {if(db.getAutoCommit())throw new SQLException("Foundation payment requires a transaction");}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS foundation_payments(escrow_id TEXT PRIMARY KEY REFERENCES foundation_escrows(id),player_uuid TEXT NOT NULL,amount INTEGER NOT NULL CHECK(amount>0),mode TEXT NOT NULL CHECK(mode IN ('LOCAL','VAULT')),provider TEXT,currency TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('WAITING','CLAIMED','PAID','DECLINED','CANCELLED','REFUND_PENDING','REFUND_CLAIMED','REFUND_FAILED','REFUNDED')),error TEXT,updated_at INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS foundation_payment_resolutions(id INTEGER PRIMARY KEY AUTOINCREMENT,escrow_id TEXT NOT NULL REFERENCES foundation_payments(escrow_id),actor TEXT NOT NULL,old_state TEXT NOT NULL,new_state TEXT NOT NULL,resolved_at INTEGER NOT NULL)");
        }
    }
    public static Receipt find(Connection db,String id)throws SQLException {
        try(var s=db.createStatement();var r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='foundation_payments'")){if(!r.next())return null;}
        try(var s=db.prepareStatement("SELECT * FROM foundation_payments WHERE escrow_id=?")){s.setString(1,id);try(var r=s.executeQuery()){return r.next()?new Receipt(id,UUID.fromString(r.getString("player_uuid")),r.getLong("amount"),r.getString("mode"),r.getString("provider"),r.getString("currency"),r.getString("state"),r.getString("error")):null;}}
    }
    public static void prepare(Connection db,String id,boolean external,String provider,String currency)throws SQLException {
        transaction(db);var p=FoundationCreationStore.find(db,id);if(p==null || !p.state().equals("CREATING"))throw new SQLException("Missing creating foundation plan");
        if(p.fee()==0)return;
        double fee=p.fee();if(!Double.isFinite(fee) || fee!=Math.rint(fee) || fee<0 || fee>9_007_199_254_740_991L)throw new IllegalArgumentException("Стоимость основания должна быть целым положительным числом допустимого размера.");
        if(external && (provider==null || provider.isBlank()))throw new SQLException("Missing foundation Vault provider");
        long amount=(long)fee;
        try(var s=db.prepareStatement("INSERT INTO foundation_payments VALUES(?,?,?,?,?,?,?,NULL,?)")){s.setString(1,id);s.setString(2,p.player().toString());s.setLong(3,amount);s.setString(4,external?"VAULT":"LOCAL");s.setString(5,external?provider:null);s.setString(6,Objects.toString(currency,""));s.setString(7,external?"WAITING":"PAID");s.setLong(8,System.currentTimeMillis());s.executeUpdate();}
        if(!external)try(var s=db.prepareStatement("UPDATE residents SET coins=coins-?,updated_at=? WHERE uuid=? AND coins>=?")){s.setLong(1,amount);s.setLong(2,System.currentTimeMillis());s.setString(3,p.player().toString());s.setLong(4,amount);if(s.executeUpdate()!=1)throw new IllegalArgumentException("Недостаточно монет для основания города.");}
    }
    private static Receipt require(Connection db,String id)throws SQLException {
        var r=find(db,id);var p=FoundationCreationStore.find(db,id);
        if(r==null || p==null || !r.player().equals(p.player()) || r.amount()!=p.fee())throw new SQLException("Missing or inconsistent foundation payment receipt");return r;
    }
    public static boolean paid(Connection db,String id)throws SQLException {
        var p=FoundationCreationStore.find(db,id);if(p==null)return false;if(p.fee()==0)return true;
        var r=find(db,id);return r!=null && r.state().equals("PAID") && r.player().equals(p.player()) && r.amount()==p.fee();
    }
    public static boolean compensatable(Connection db,String id)throws SQLException {
        var p=FoundationCreationStore.find(db,id);if(p==null)return false;if(p.fee()==0)return true;
        var r=require(db,id);return List.of("WAITING","PAID","DECLINED","CANCELLED","REFUNDED").contains(r.state());
    }
    private static void state(Connection db,String id,String expected,String next,String error)throws SQLException {
        transaction(db);try(var s=db.prepareStatement("UPDATE foundation_payments SET state=?,error=?,updated_at=? WHERE escrow_id=? AND state=?")){s.setString(1,next);s.setString(2,error==null?null:error.substring(0,Math.min(error.length(),1000)));s.setLong(3,System.currentTimeMillis());s.setString(4,id);s.setString(5,expected);if(s.executeUpdate()!=1)throw new SQLException("Foundation payment state changed");}
    }
    public static void claim(Connection db,String id,boolean refund)throws SQLException {
        transaction(db);var r=require(db,id);if(!r.mode().equals("VAULT"))throw new SQLException("Only external foundation payments are dispatched");
        if(!FoundationCreationStore.find(db,id).state().equals("CREATING") || (refund && !FoundationCreationRollback.completed(db,id)))throw new SQLException("Foundation is not ready for external payment dispatch");
        state(db,id,refund?"REFUND_PENDING":"WAITING",refund?"REFUND_CLAIMED":"CLAIMED",null);
    }
    public static void acknowledged(Connection db,String id,boolean refund,boolean success,String error)throws SQLException {
        state(db,id,refund?"REFUND_CLAIMED":"CLAIMED",refund?(success?"REFUNDED":"REFUND_FAILED"):(success?"PAID":"DECLINED"),error);
    }
    public static void uncertain(Connection db,String id,boolean refund,String error)throws SQLException {
        state(db,id,refund?"REFUND_CLAIMED":"CLAIMED",refund?"REFUND_CLAIMED":"CLAIMED",error);
    }
    /** Called in the same transaction as deletion of pristine creation records. */
    public static boolean refundForRollback(Connection db,String id)throws SQLException {
        transaction(db);var p=FoundationCreationStore.find(db,id);if(p==null)throw new SQLException("Missing foundation plan");if(p.fee()==0)return true;
        var r=require(db,id);
        switch(r.state()) {
            case "WAITING" -> {if(!r.mode().equals("VAULT"))throw new SQLException("Unexpected local foundation payment state");state(db,id,"WAITING","CANCELLED",null);return true;}
            case "DECLINED","CANCELLED","REFUNDED" -> {return true;}
            case "PAID" -> {
                if(r.mode().equals("VAULT")){state(db,id,"PAID","REFUND_PENDING",null);return false;}
                try(var s=db.prepareStatement("UPDATE residents SET coins=coins+?,updated_at=? WHERE uuid=? AND coins<=?")){s.setLong(1,r.amount());s.setLong(2,System.currentTimeMillis());s.setString(3,r.player().toString());s.setLong(4,Long.MAX_VALUE-r.amount());if(s.executeUpdate()!=1)throw new SQLException("Foundation refund balance overflow or missing resident");}
                state(db,id,"PAID","REFUNDED",null);return true;
            }
            default -> throw new SQLException("Foundation payment awaits external reconciliation");
        }
    }
    public static void finishCancellation(Connection db,String id)throws SQLException {
        transaction(db);if(!FoundationCreationRollback.completed(db,id))throw new SQLException("Foundation creation is not compensated");
        if(!cancellationReady(db,id))return;
        FoundationEscrowStore.cancel(db,id,true);
    }
    public static boolean cancellationReady(Connection db,String id)throws SQLException {
        var p=FoundationCreationStore.find(db,id);if(p==null)throw new SQLException("Missing foundation plan");
        return p.fee()==0 || List.of("REFUNDED","DECLINED","CANCELLED").contains(require(db,id).state());
    }
    public static void resolve(Connection db,String id,String action,String actor)throws SQLException {
        transaction(db);var r=require(db,id);var p=FoundationCreationStore.find(db,id);
        if(!r.mode().equals("VAULT") || !p.state().equals("CREATING"))throw new IllegalArgumentException("Сверять можно только незавершённый внешний платёж основания.");
        String next=switch(action) {
            case "charged" -> r.state().equals("CLAIMED")?"PAID":null;
            case "notcharged" -> r.state().equals("CLAIMED")?"DECLINED":null;
            case "refunded" -> List.of("REFUND_CLAIMED","REFUND_FAILED").contains(r.state())?"REFUNDED":null;
            case "refundnotpaid" -> List.of("REFUND_CLAIMED","REFUND_FAILED").contains(r.state())?"REFUND_PENDING":null;
            default -> null;
        };
        if(next==null)throw new IllegalArgumentException("Решение не подходит текущей стадии платежа. Сначала проверьте status и журнал провайдера.");
        state(db,id,r.state(),next,"Operator reconciliation: "+actor);
        try(var s=db.prepareStatement("INSERT INTO foundation_payment_resolutions(escrow_id,actor,old_state,new_state,resolved_at) VALUES(?,?,?,?,?)")){s.setString(1,id);s.setString(2,actor);s.setString(3,r.state());s.setString(4,next);s.setLong(5,System.currentTimeMillis());s.executeUpdate();}
    }
    public static String stateName(String state){return switch(state){case "WAITING"->"списание ещё не отправлено";case "CLAIMED"->"результат списания требует сверки";case "PAID"->"оплачено";case "DECLINED"->"списание отклонено";case "CANCELLED"->"отменено до списания";case "REFUND_PENDING"->"ожидается возврат монет";case "REFUND_CLAIMED"->"результат возврата требует сверки";case "REFUND_FAILED"->"провайдер отклонил возврат";case "REFUNDED"->"монеты возвращены";default->"неизвестная стадия";};}
}
