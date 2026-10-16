package com.avrgaming.civcraft.modern.trade;

import java.sql.*;
import java.math.BigDecimal;
import java.util.*;

/** Inventory intents survive restart; callers complete them only after a world flush. */
public final class TradeShipOperations {
    private TradeShipOperations() {}
    public record Clock(long build,long period,long hour,TradeShipProgress.State progress) {}
    public record Pending(String id,Clock before,long hour,long town,TradeShipIncomeStore.Plan payment,
                          TradeShipProgress.State after,String result,int consumed,byte[] containers) {
        public Pending {containers=containers.clone();}
        @Override public byte[] containers(){return containers.clone();}
    }
    public static void install(Connection db)throws SQLException {
        TradeShipStore.install(db);
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS trade_ship_clock(build_id INTEGER PRIMARY KEY REFERENCES structure_builds(id) ON DELETE CASCADE,period INTEGER NOT NULL,last_hour INTEGER NOT NULL)");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS trade_ship_operations(
                id TEXT PRIMARY KEY,build_id INTEGER NOT NULL,period INTEGER NOT NULL,hour INTEGER NOT NULL,before_hour INTEGER NOT NULL,
                before_level INTEGER NOT NULL,before_count INTEGER NOT NULL,after_level INTEGER NOT NULL,after_count INTEGER NOT NULL,
                town_id INTEGER NOT NULL,civ_id INTEGER NOT NULL,town_amount TEXT NOT NULL,civ_amount TEXT NOT NULL,culture INTEGER NOT NULL,
                result TEXT NOT NULL,consumed INTEGER NOT NULL,containers BLOB NOT NULL,status TEXT NOT NULL DEFAULT 'PREPARED',
                UNIQUE(build_id,period,hour))
                """);
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_pending_ship_operation ON trade_ship_operations(build_id) WHERE status='PREPARED'");
        }
    }
    private static void transaction(Connection db)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Trade ship operation requires a transaction");
    }
    public static boolean installed(Connection db)throws SQLException {
        try(var s=db.createStatement();var r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='trade_ship_operations'")){return r.next();}
    }
    public static void requireBuildUnlocked(Connection db,long build)throws SQLException {com.avrgaming.civcraft.modern.trade.GoodieChestStore.requireStructureUnlocked(db,build,false);requireUnlocked(db,"build_id",build);}
    public static void requireTownUnlocked(Connection db,long town)throws SQLException {com.avrgaming.civcraft.modern.trade.GoodieChestStore.requireStructureUnlocked(db,town,true);requireUnlocked(db,"town_id",town);}
    private static void requireUnlocked(Connection db,String column,long id)throws SQLException {
        if(!installed(db))return;
        try(var s=db.prepareStatement("SELECT 1 FROM trade_ship_operations WHERE "+column+"=? AND status='PREPARED' LIMIT 1")) {
            s.setLong(1,id);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Торговая операция корабля ещё сохраняется. Дождитесь восстановления.");}
        }
    }
    public static void resetDestroyed(Connection db,long build)throws SQLException {
        if(!installed(db))return;
        requireBuildUnlocked(db,build);
        try(var s=db.prepareStatement("SELECT 1 FROM structure_builds WHERE id=? AND structure_id='ti_trade_ship' AND status='WAR_DESTROYED'")) {
            s.setLong(1,build);try(var r=s.executeQuery()){if(!r.next())return;}
        }
        TradeShipStore.progress(db,build,new TradeShipProgress.State(1,0));
    }
    public static Clock clock(Connection db,long build)throws SQLException {
        try(var s=db.prepareStatement("SELECT period,last_hour FROM trade_ship_clock WHERE build_id=?")) {
            s.setLong(1,build);try(var r=s.executeQuery()){return r.next()?new Clock(build,r.getLong(1),r.getLong(2),TradeShipStore.progress(db,build)):null;}
        }
    }
    public static Clock initialize(Connection db,long build,long period,long hour)throws SQLException {
        transaction(db);if(period<1000 || hour<0)throw new IllegalArgumentException("Invalid trade ship clock");
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO trade_ship_clock VALUES(?,?,?)")) {
            s.setLong(1,build);s.setLong(2,period);s.setLong(3,hour);s.executeUpdate();
        }
        return clock(db,build);
    }
    public static void start(Connection db,long period,long hour)throws SQLException {
        transaction(db);if(period<1000 || hour<0)throw new IllegalArgumentException("Invalid trade ship clock");
        if(!pending(db).isEmpty())throw new SQLException("Trade ship recovery must finish before starting clock");
        try(var s=db.prepareStatement("UPDATE trade_ship_clock SET period=?,last_hour=CASE WHEN period!=? THEN ? ELSE MAX(last_hour,?) END")) {
            s.setLong(1,period);s.setLong(2,period);s.setLong(3,hour);s.setLong(4,hour);s.executeUpdate();
        }
    }
    public static Pending prepare(Connection db,String id,Clock before,long hour,TradeShipProgress.Outcome outcome,
                                  TradeShipIncomeStore.Plan payment,byte[] containers)throws SQLException {
        transaction(db);
        com.avrgaming.civcraft.modern.trade.GoodieChestStore.requireImagesUnlocked(db,containers);
        if(id==null || id.isBlank() || hour<=before.hour() || outcome.state().level()>10 || containers==null || containers.length==0)
            throw new IllegalArgumentException("Invalid trade ship intent");
        if(!before.equals(clock(db,before.build())))throw new SQLException("Trade ship progress changed before preparation");
        long town,civ;
        try(var s=db.prepareStatement("SELECT t.id,t.civ_id FROM active_structure_builds b JOIN towns t ON t.id=b.town_id WHERE b.id=? AND b.structure_id='ti_trade_ship' AND b.status='COMPLETE'")) {
            s.setLong(1,before.build());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Trade ship is not complete");town=r.getLong(1);civ=r.getLong(2);}
        }
        if(civ!=payment.civilization())throw new SQLException("Trade ship owner changed before preparation");
        try(var s=db.prepareStatement("INSERT INTO trade_ship_operations VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PREPARED')")) {
            int i=1;s.setString(i++,id);s.setLong(i++,before.build());s.setLong(i++,before.period());s.setLong(i++,hour);s.setLong(i++,before.hour());
            s.setInt(i++,before.progress().level());s.setInt(i++,before.progress().count());s.setInt(i++,outcome.state().level());s.setInt(i++,outcome.state().count());
            s.setLong(i++,town);s.setLong(i++,civ);s.setString(i++,payment.payment().town().toPlainString());s.setString(i++,payment.payment().civilization().toPlainString());
            s.setLong(i++,payment.payment().culture());s.setString(i++,outcome.result().name());s.setInt(i++,outcome.consumed());s.setBytes(i,containers);s.executeUpdate();
        }
        return new Pending(id,before,hour,town,payment,outcome.state(),outcome.result().name(),outcome.consumed(),containers);
    }
    public static List<Pending> pending(Connection db)throws SQLException {
        var result=new ArrayList<Pending>();
        try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM trade_ship_operations WHERE status='PREPARED' ORDER BY hour,build_id")){while(r.next())result.add(read(r));}
        return List.copyOf(result);
    }
    public static boolean complete(Connection db,String id)throws SQLException {
        transaction(db);Pending operation;
        try(var s=db.prepareStatement("SELECT * FROM trade_ship_operations WHERE id=?")) {
            s.setString(1,id);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Unknown trade ship operation");if(r.getString("status").equals("COMPLETE"))return false;operation=read(r);}
        }
        if(!operation.before().equals(clock(db,operation.before().build())))throw new SQLException("Trade ship progress changed during recovery");
        TradeShipStore.progress(db,operation.before().build(),operation.after());
        TradeShipIncomeStore.credit(db,id,operation.town(),operation.payment());
        try(var s=db.prepareStatement("UPDATE trade_ship_clock SET last_hour=? WHERE build_id=?")) {
            s.setLong(1,operation.hour());s.setLong(2,operation.before().build());if(s.executeUpdate()!=1)throw new SQLException("Trade ship clock missing");
        }
        try(var s=db.prepareStatement("UPDATE trade_ship_operations SET status='COMPLETE',containers=X'' WHERE id=? AND status='PREPARED'")) {
            s.setString(1,id);if(s.executeUpdate()!=1)throw new SQLException("Trade ship receipt changed");
        }
        return true;
    }
    private static Pending read(ResultSet r)throws SQLException {
        var before=new Clock(r.getLong("build_id"),r.getLong("period"),r.getLong("before_hour"),new TradeShipProgress.State(r.getInt("before_level"),r.getInt("before_count")));
        var payment=new TradeShipIncomeStore.Plan(r.getLong("civ_id"),new TradeShipIncome.Payment(new BigDecimal(r.getString("town_amount")),new BigDecimal(r.getString("civ_amount")),r.getLong("culture")));
        return new Pending(r.getString("id"),before,r.getLong("hour"),r.getLong("town_id"),payment,new TradeShipProgress.State(r.getInt("after_level"),r.getInt("after_count")),r.getString("result"),r.getInt("consumed"),r.getBytes("containers"));
    }
}
