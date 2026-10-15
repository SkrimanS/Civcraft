package com.avrgaming.civcraft.modern.trade;

import java.sql.*;
import java.util.*;

public final class TradeGoodIncomeStore {
    private TradeGoodIncomeStore() {}
    public static void install(Connection db)throws SQLException {
        TradeShipIncomeStore.install(db);
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS goodie_income_clock(period INTEGER PRIMARY KEY,last_hour INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS goodie_income_hours(period INTEGER NOT NULL,hour INTEGER NOT NULL,town_id INTEGER NOT NULL,civ_id INTEGER NOT NULL,town_amount TEXT NOT NULL,civ_amount TEXT NOT NULL,PRIMARY KEY(period,hour,town_id))");
        }
    }
    public static void start(Connection db,long period,long hour)throws SQLException {
        validate(period,hour);
        try(var s=db.prepareStatement("INSERT INTO goodie_income_clock VALUES(?,?) ON CONFLICT(period) DO UPDATE SET last_hour=MAX(last_hour,excluded.last_hour)")){s.setLong(1,period);s.setLong(2,hour);s.executeUpdate();}
    }
    /** Advances even when war disables trade, so ending a battle cannot pay a skipped boundary. */
    public static boolean begin(Connection db,long period,long hour)throws SQLException {
        transaction(db);validate(period,hour);
        try(var s=db.prepareStatement("UPDATE goodie_income_clock SET last_hour=? WHERE period=? AND last_hour<?")){s.setLong(1,hour);s.setLong(2,period);s.setLong(3,hour);return s.executeUpdate()==1;}
    }
    public static boolean boundary(Connection db,long period,long hour,long now)throws SQLException {
        return begin(db,period,hour)&&!com.avrgaming.civcraft.modern.war.WarClockStore.active(db,now);
    }
    public static double structureRate(Connection db,long town,TradeGoodIncome income)throws SQLException {
        double total=0;
        try(var s=db.prepareStatement("SELECT b.structure_id,COUNT(*) FROM structure_builds b LEFT JOIN building_combat_health h ON h.build_id=b.id WHERE b.town_id=? AND b.status='COMPLETE' AND (b.max_hitpoints=0 OR COALESCE(h.hitpoints,b.max_hitpoints)>0) GROUP BY b.structure_id")) {
            s.setLong(1,town);try(var r=s.executeQuery()){while(r.next())total+=income.structureRate(r.getString(1))*r.getInt(2);}
        }
        return total;
    }
    public static List<String> payable(Connection db,long town)throws SQLException {
        var result=new ArrayList<String>();
        try(var s=db.prepareStatement("""
            SELECT g.good_id FROM bonus_goodies g
            JOIN goodie_frames f ON f.frame_key=g.holder_id AND f.is_source=0
            JOIN structure_builds hall ON hall.id=f.build_id AND hall.town_id=g.host_town
            JOIN structure_builds origin ON origin.id=g.origin_build
            LEFT JOIN building_combat_health h ON h.build_id=origin.id
            WHERE g.holder_kind='FRAME' AND g.host_town=? AND hall.status='COMPLETE'
              AND hall.structure_id IN ('s_capitol','s_townhall') AND origin.status='COMPLETE'
              AND origin.structure_id IN ('ti_trade_outpost','ti_fishing_boat')
              AND NOT EXISTS(SELECT 1 FROM goodie_transfers transfer WHERE transfer.origin_build=g.origin_build AND transfer.status='PREPARED')
              AND (origin.max_hitpoints=0 OR COALESCE(h.hitpoints,origin.max_hitpoints)>0)
              AND EXISTS(SELECT 1 FROM culture_regions cr WHERE cr.world=origin.world
                         AND cr.chunk_x=(origin.x >> 4) AND cr.chunk_z=(origin.z >> 4))
            ORDER BY g.origin_build
            """)){s.setLong(1,town);try(var r=s.executeQuery()){while(r.next())result.add(r.getString(1));}}
        return List.copyOf(result);
    }
    public static boolean credit(Connection db,long period,long hour,long town,long civ,TradeShipIncome.Payment payment)throws SQLException {
        transaction(db);validate(period,hour);
        if(payment.culture()!=0||payment.town().signum()<0||payment.civilization().signum()<0)throw new IllegalArgumentException("Invalid goodie payment");
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO goodie_income_hours VALUES(?,?,?,?,?,?)")) {
            s.setLong(1,period);s.setLong(2,hour);s.setLong(3,town);s.setLong(4,civ);s.setString(5,payment.town().toPlainString());s.setString(6,payment.civilization().toPlainString());if(s.executeUpdate()==0)return false;
        }
        // Reuse the existing fractional treasuries: ship and goodie income must combine before whole-coin credit.
        if(!TradeShipIncomeStore.credit(db,"goodie:"+period+":"+hour+":"+town,town,new TradeShipIncomeStore.Plan(civ,payment)))throw new SQLException("Goodie payout journal mismatch");
        return true;
    }
    private static void transaction(Connection db)throws SQLException {if(db.getAutoCommit())throw new SQLException("Goodie income requires a transaction");}
    private static void validate(long period,long hour){if(period<1000||hour<0)throw new IllegalArgumentException("Invalid goodie income clock");}
}
