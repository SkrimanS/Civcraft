package com.avrgaming.civcraft.modern.trade;

import java.sql.*;
import java.math.BigDecimal;
import com.avrgaming.civcraft.modern.town.UpkeepStore;

public final class TradeShipIncomeStore {
    private TradeShipIncomeStore() {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS trade_ship_town_fractions(town_id INTEGER PRIMARY KEY REFERENCES towns(id) ON DELETE CASCADE,amount TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS trade_ship_civ_fractions(civ_id INTEGER PRIMARY KEY REFERENCES civilizations(id) ON DELETE CASCADE,amount TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS trade_ship_income_receipts(receipt_id TEXT PRIMARY KEY,town_id INTEGER NOT NULL REFERENCES towns(id) ON DELETE CASCADE,civ_id INTEGER NOT NULL REFERENCES civilizations(id) ON DELETE CASCADE,town_amount TEXT NOT NULL,civ_amount TEXT NOT NULL,culture INTEGER NOT NULL)");
        }
    }
    public record Plan(long civilization,TradeShipIncome.Payment payment) {}
    public static Plan plan(Connection db,long town,double money,long culture,TradeShipIncome.Rates rates)throws SQLException {
        long civ;double tax;
        try(var s=db.prepareStatement("SELECT t.civ_id,c.income_tax_rate FROM towns t JOIN civilizations c ON c.id=t.civ_id WHERE t.id=?")) {
            s.setLong(1,town);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Trade ship town missing");civ=r.getLong(1);tax=r.getDouble(2);}
        }
        boolean ingermanland=wonder(db,civ,"w_grand_ship_ingermanland"),great=wonder(db,civ,"w_great_lighthouse"),local;
        // Legacy getStructureTypeCount includes registered construction and ruins.
        try(var s=db.prepareStatement("SELECT 1 FROM structure_builds WHERE town_id=? AND structure_id='s_lighthouse' AND status NOT IN ('DEMOLISHED','CANCELLED','DEMOLISH_PENDING','ROLLBACK_PENDING') LIMIT 1")) {
            s.setLong(1,town);try(var r=s.executeQuery()){local=r.next();}
        }
        return new Plan(civ,TradeShipIncome.calculate(money,culture,tax,rates,ingermanland,great,local));
    }
    private static boolean wonder(Connection db,long civ,String type)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM towns t JOIN structure_builds b ON b.town_id=t.id LEFT JOIN building_combat_health h ON h.build_id=b.id WHERE t.civ_id=? AND b.structure_id=? AND b.status='COMPLETE' AND COALESCE(h.hitpoints,b.max_hitpoints)>0 LIMIT 1")) {
            s.setLong(1,civ);s.setString(2,type);try(var r=s.executeQuery()){return r.next();}
        }
    }
    /** Must share the transaction that commits ship progress and its inventory receipt. */
    public static boolean credit(Connection db,String receipt,long town,Plan plan)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Trade ship credit requires a transaction");
        if(receipt==null || receipt.isBlank())throw new IllegalArgumentException("Не найдена квитанция торгового корабля");
        var payment=plan.payment();
        if(payment.town().signum()<0 || payment.civilization().signum()<0 || payment.culture()<0)
            throw new IllegalArgumentException("Неверная выплата торгового корабля");
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO trade_ship_income_receipts VALUES(?,?,?,?,?,?)")) {
            s.setString(1,receipt);s.setLong(2,town);s.setLong(3,plan.civilization());s.setString(4,payment.town().toPlainString());
            s.setString(5,payment.civilization().toPlainString());s.setLong(6,payment.culture());if(s.executeUpdate()==0)return false;
        }
        deposit(db,true,town,payment.town());
        BigDecimal tribute=payment.civilization();
        try(var old=db.prepareStatement("SELECT amount FROM trade_ship_civ_fractions WHERE civ_id=?")){old.setLong(1,plan.civilization());try(var r=old.executeQuery()){if(r.next())tribute=tribute.add(new BigDecimal(r.getString(1)));}}
        com.avrgaming.civcraft.modern.research.CivilizationScienceStore.creditTax(db,plan.civilization(),tribute);
        try(var old=db.prepareStatement("DELETE FROM trade_ship_civ_fractions WHERE civ_id=?")){old.setLong(1,plan.civilization());old.executeUpdate();}
        if(payment.culture()>0)try(var s=db.prepareStatement("UPDATE towns SET accumulated_culture=accumulated_culture+? WHERE id=? AND accumulated_culture<=?")) {
            s.setLong(1,payment.culture());s.setLong(2,town);s.setLong(3,Long.MAX_VALUE-payment.culture());
            if(s.executeUpdate()!=1)throw new SQLException("Trade ship culture overflow or town missing");
        }
        return true;
    }
    private static void deposit(Connection db,boolean town,long owner,BigDecimal amount)throws SQLException {
        String table=town?"trade_ship_town_fractions":"trade_ship_civ_fractions",key=town?"town_id":"civ_id";
        BigDecimal total=amount;
        try(var s=db.prepareStatement("SELECT amount FROM "+table+" WHERE "+key+"=?")) {
            s.setLong(1,owner);try(var r=s.executeQuery()){if(r.next())total=total.add(new BigDecimal(r.getString(1)));}
        }
        long whole;
        try {whole=total.toBigInteger().longValueExact();}catch(ArithmeticException failure){throw new SQLException("Trade ship income overflow",failure);}
        if(whole>0)UpkeepStore.deposit(db,town?"towns":"civilizations",owner,whole);
        try(var s=db.prepareStatement("INSERT INTO "+table+" VALUES(?,?) ON CONFLICT("+key+") DO UPDATE SET amount=excluded.amount")) {
            s.setLong(1,owner);s.setString(2,total.subtract(BigDecimal.valueOf(whole)).toPlainString());s.executeUpdate();
        }
    }
}
