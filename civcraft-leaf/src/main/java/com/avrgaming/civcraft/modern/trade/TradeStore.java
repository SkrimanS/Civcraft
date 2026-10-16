package com.avrgaming.civcraft.modern.trade;

import java.sql.*;
import java.util.*;
import com.avrgaming.civcraft.modern.build.BlockPlacement;

/** Durable inventory intent and wallet escrow; callers hold the SQLite transaction. */
public final class TradeStore {
    private TradeStore() {}
    public record Pending(String id,String player,byte[] inventory,long debit,long credit,Long town,long fee) {}
    public record Checkpoint(String id,byte[] inventory) {}
    public static void install(Connection db)throws SQLException {
        GrocerStore.install(db);
        LibraryStore.install(db);
        BuildingSupplyStore.install(db);
        BlacksmithStore.install(db);
        try(var statement=db.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS structure_trade_points(
                    world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,
                    build_id INTEGER NOT NULL REFERENCES structure_builds(id) ON DELETE CASCADE,
                    action TEXT NOT NULL,argument TEXT NOT NULL,PRIMARY KEY(world,x,y,z))
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS market_prices(
                    ident TEXT PRIMARY KEY,sell INTEGER NOT NULL,buy INTEGER NOT NULL,counter INTEGER NOT NULL)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS town_trade_settings(
                    town_id INTEGER PRIMARY KEY REFERENCES towns(id) ON DELETE CASCADE,
                    bank_level INTEGER NOT NULL DEFAULT 1,bank_fee REAL NOT NULL DEFAULT 0.05)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS trade_receipts(
                    id TEXT PRIMARY KEY,player_uuid TEXT NOT NULL,inventory BLOB NOT NULL,
                    debit INTEGER NOT NULL DEFAULT 0,credit INTEGER NOT NULL,fee_town_id INTEGER,fee INTEGER NOT NULL,refunded_fee INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'PREPARED',created_at INTEGER NOT NULL)
                    """);
            statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_one_pending_trade ON trade_receipts(player_uuid) WHERE status='PREPARED'");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_trade_checkpoints(
                    player_uuid TEXT PRIMARY KEY REFERENCES residents(uuid) ON DELETE CASCADE,
                    trade_id TEXT NOT NULL,inventory BLOB NOT NULL)
                    """);
            boolean hasDebit=false,hasRefundedFee=false;
            try(var columns=statement.executeQuery("PRAGMA table_info(trade_receipts)")){while(columns.next()) {
                if(columns.getString("name").equals("debit"))hasDebit=true;
                if(columns.getString("name").equals("refunded_fee"))hasRefundedFee=true;
            }}
            if(!hasDebit)statement.executeUpdate("ALTER TABLE trade_receipts ADD COLUMN debit INTEGER NOT NULL DEFAULT 0");
            if(!hasRefundedFee)statement.executeUpdate("ALTER TABLE trade_receipts ADD COLUMN refunded_fee INTEGER NOT NULL DEFAULT 0");
        }
    }
    public static void recordPoints(Connection db,long buildId,Collection<BlockPlacement> placements)throws SQLException {
        try(var insert=db.prepareStatement("INSERT OR REPLACE INTO structure_trade_points VALUES(?,?,?,?,?,?,?)")) {
            for(var block:placements) {
                var point=TradePoint.parse(block.signLines()).orElse(null);
                if(point==null)continue;
                insert.setString(1,block.world());insert.setInt(2,block.x());insert.setInt(3,block.y());insert.setInt(4,block.z());
                insert.setLong(5,buildId);insert.setString(6,point.action());insert.setString(7,point.argument());insert.addBatch();
            }
            insert.executeBatch();
        }
    }
    public static Pending prepare(Connection db,String id,String player,byte[] inventory,long debit,long credit,Long town,long fee)throws SQLException {
        if(debit<0 || credit<0 || fee<0)throw new IllegalArgumentException("Negative trade amount");
        if(pending(db,player)!=null)throw new IllegalArgumentException("Предыдущий обмен ещё не завершён.");
        try(var query=db.prepareStatement("SELECT coins FROM residents WHERE uuid=?")) {
            query.setString(1,player);try(var result=query.executeQuery()) {
                if(!result.next())throw new SQLException("Missing trade resident");
                long coins=result.getLong(1);
                if(coins<debit)throw new IllegalArgumentException("Недостаточно монет: требуется "+debit+".");
                if(coins-debit>Long.MAX_VALUE-credit)throw new IllegalArgumentException("Баланс слишком велик для этого обмена.");
            }
        }
        if(debit>0)try(var update=db.prepareStatement("UPDATE residents SET coins=coins-? WHERE uuid=? AND coins>=?")) {
            update.setLong(1,debit);update.setString(2,player);update.setLong(3,debit);
            if(update.executeUpdate()!=1)throw new IllegalArgumentException("Недостаточно монет: требуется "+debit+".");
        }
        try(var insert=db.prepareStatement("INSERT INTO trade_receipts(id,player_uuid,inventory,credit,fee_town_id,fee,created_at,debit) VALUES(?,?,?,?,?,?,?,?)")) {
            insert.setString(1,id);insert.setString(2,player);insert.setBytes(3,inventory);insert.setLong(4,credit);
            if(town==null)insert.setNull(5,Types.BIGINT);else insert.setLong(5,town);
            insert.setLong(6,fee);insert.setLong(7,System.currentTimeMillis());insert.setLong(8,debit);insert.executeUpdate();
        }
        return new Pending(id,player,inventory,debit,credit,town,fee);
    }
    public static Pending pending(Connection db,String player)throws SQLException {
        try(var query=db.prepareStatement("SELECT * FROM trade_receipts WHERE player_uuid=? AND status='PREPARED'")) {
            query.setString(1,player);try(var result=query.executeQuery()) {
                if(!result.next())return null;
                Long town=result.getObject("fee_town_id")==null?null:result.getLong("fee_town_id");
                return new Pending(result.getString("id"),player,result.getBytes("inventory"),result.getLong("debit"),result.getLong("credit"),town,result.getLong("fee"));
            }
        }
    }
    public static boolean finish(Connection db,Pending receipt)throws SQLException {
        try(var update=db.prepareStatement("UPDATE trade_receipts SET status='COMPLETE',inventory=X'' WHERE id=? AND player_uuid=? AND status='PREPARED'")) {
            update.setString(1,receipt.id());update.setString(2,receipt.player());if(update.executeUpdate()!=1)return false;
        }
        if(receipt.credit()>0)try(var update=db.prepareStatement("UPDATE residents SET coins=coins+? WHERE uuid=? AND coins<=?")) {
            update.setLong(1,receipt.credit());update.setString(2,receipt.player());update.setLong(3,Long.MAX_VALUE-receipt.credit());if(update.executeUpdate()!=1)throw new SQLException("Invalid trade resident balance");
        }
        if(receipt.fee()>0 && receipt.town()!=null)try(var update=db.prepareStatement("UPDATE towns SET coins=coins+? WHERE id=? AND coins<=?")) {
            update.setLong(1,receipt.fee());update.setLong(2,receipt.town());update.setLong(3,Long.MAX_VALUE-receipt.fee());
            if(update.executeUpdate()!=1) {
                try(var query=db.prepareStatement("SELECT 1 FROM towns WHERE id=?")) {
                    query.setLong(1,receipt.town());try(var result=query.executeQuery()){if(result.next())throw new SQLException("Invalid fee town balance");}
                }
                // A town removed while its visitor's trade was pending cannot receive the fee.
                try(var refund=db.prepareStatement("UPDATE residents SET coins=coins+? WHERE uuid=? AND coins<=?")) {
                    refund.setLong(1,receipt.fee());refund.setString(2,receipt.player());refund.setLong(3,Long.MAX_VALUE-receipt.fee());
                    if(refund.executeUpdate()!=1)throw new SQLException("Invalid refunded trade balance");
                }
                try(var audit=db.prepareStatement("UPDATE trade_receipts SET refunded_fee=? WHERE id=?")) {
                    audit.setLong(1,receipt.fee());audit.setString(2,receipt.id());audit.executeUpdate();
                }
            }
        }
        saveCheckpoint(db,receipt.player(),receipt.id(),receipt.inventory());
        return true;
    }
    public static void saveCheckpoint(Connection db,String player,String id,byte[] inventory)throws SQLException {
        try(var update=db.prepareStatement("""
                INSERT INTO player_trade_checkpoints(player_uuid,trade_id,inventory) VALUES(?,?,?)
                ON CONFLICT(player_uuid) DO UPDATE SET trade_id=excluded.trade_id,inventory=excluded.inventory
                """)) {
            update.setString(1,player);update.setString(2,id);update.setBytes(3,inventory);update.executeUpdate();
        }
    }
    public static Checkpoint checkpoint(Connection db,String player)throws SQLException {
        try(var query=db.prepareStatement("SELECT trade_id,inventory FROM player_trade_checkpoints WHERE player_uuid=?")) {
            query.setString(1,player);try(var result=query.executeQuery()){return result.next()?new Checkpoint(result.getString(1),result.getBytes(2)):null;}
        }
    }
    public static MarketPrice price(Connection db,String ident,MarketPrice initial)throws SQLException {
        try(var insert=db.prepareStatement("INSERT OR IGNORE INTO market_prices VALUES(?,?,?,?)")) {
            insert.setString(1,ident);insert.setLong(2,initial.sell());insert.setLong(3,initial.buy());insert.setInt(4,initial.counter());insert.executeUpdate();
        }
        try(var query=db.prepareStatement("SELECT sell,buy,counter FROM market_prices WHERE ident=?")) {
            query.setString(1,ident);try(var result=query.executeQuery()){if(!result.next())throw new SQLException("Missing market price");return new MarketPrice(result.getLong(1),result.getLong(2),result.getInt(3));}
        }
    }
    public static void updatePrice(Connection db,String ident,MarketPrice price)throws SQLException {
        try(var update=db.prepareStatement("UPDATE market_prices SET sell=?,buy=?,counter=? WHERE ident=?")) {
            update.setLong(1,price.sell());update.setLong(2,price.buy());update.setInt(3,price.counter());update.setString(4,ident);update.executeUpdate();
        }
    }
}
