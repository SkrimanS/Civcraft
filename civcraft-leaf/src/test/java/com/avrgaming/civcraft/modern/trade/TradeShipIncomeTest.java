package com.avrgaming.civcraft.modern.trade;

import java.math.BigDecimal;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradeShipIncomeTest {
    final TradeShipIncome.Rates rates=new TradeShipIncome.Rates(1.3,1.2,1.2);
    private Connection database()throws SQLException {
        var db=DriverManager.getConnection("jdbc:sqlite::memory:");
        try(var s=db.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("CREATE TABLE civilizations(id INTEGER PRIMARY KEY,coins INTEGER DEFAULT 0,debt INTEGER DEFAULT 0,days_in_debt INTEGER DEFAULT 0,income_tax_rate REAL DEFAULT 0.1)");
            s.execute("CREATE TABLE towns(id INTEGER PRIMARY KEY,civ_id INTEGER,coins INTEGER DEFAULT 0,debt INTEGER DEFAULT 0,days_in_debt INTEGER DEFAULT 0,accumulated_culture INTEGER DEFAULT 0)");
            s.execute("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY,town_id INTEGER,structure_id TEXT,status TEXT,max_hitpoints INTEGER DEFAULT 100)");
            s.execute("CREATE TABLE building_combat_health(build_id INTEGER,hitpoints INTEGER)");
            s.execute("INSERT INTO civilizations(id) VALUES(1),(2)");
            s.execute("INSERT INTO towns(id,civ_id) VALUES(1,1),(2,1),(3,2)");
        }
        TradeShipIncomeStore.install(db);return db;
    }
    private long number(Connection db,String sql)throws SQLException {
        try(var s=db.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}
    }
    @Test void incomeRetainsFractionsAndAppliesMultipliersBeforeTax() {
        assertEquals(rates,TradeShipIncome.rates(new java.io.File("src/main/resources/civcraft/data")));
        var payment=TradeShipIncome.calculate(10,7,.1,rates,true,true,true);
        assertEquals(0,new BigDecimal("16.848").compareTo(payment.town()));
        assertEquals(0,new BigDecimal("1.872").compareTo(payment.civilization()));assertEquals(7,payment.culture());
        var tiny=TradeShipIncome.calculate(.5,3,.1,rates,true,true,true);
        assertEquals(0,tiny.town().signum());assertEquals(3,tiny.culture());
    }
    @Test void wonderBonusesFollowCurrentCivAndLocalLighthouseUsesRegistrationCount()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("INSERT INTO structure_builds(id,town_id,structure_id,status) VALUES(1,2,'w_great_lighthouse','COMPLETE'),(2,3,'w_grand_ship_ingermanland','COMPLETE'),(3,1,'s_lighthouse','BUILDING')");
            assertEquals(0,new BigDecimal("12.96").compareTo(TradeShipIncomeStore.plan(db,1,10,0,rates).payment().town()));
            db.createStatement().execute("UPDATE towns SET civ_id=1 WHERE id=3");
            assertEquals(0,new BigDecimal("16.848").compareTo(TradeShipIncomeStore.plan(db,1,10,0,rates).payment().town()));
            db.createStatement().execute("INSERT INTO building_combat_health VALUES(1,0)");
            assertEquals(0,new BigDecimal("14.04").compareTo(TradeShipIncomeStore.plan(db,1,10,0,rates).payment().town()));
            db.createStatement().execute("UPDATE structure_builds SET status='DEMOLISHED' WHERE id=3");
            assertEquals(0,new BigDecimal("11.7").compareTo(TradeShipIncomeStore.plan(db,1,10,0,rates).payment().town()));
        }
    }
    @Test void receiptsPreventDoubleCreditAndFractionalTaxAccumulatesWithoutLoss()throws Exception {
        try(var db=database()) {
            db.setAutoCommit(false);
            var plan=TradeShipIncomeStore.plan(db,1,1,2,rates);
            for(int i=0;i<10;i++) {
                assertTrue(TradeShipIncomeStore.credit(db,"hour-"+i,1,plan));db.commit();
                assertFalse(TradeShipIncomeStore.credit(db,"hour-"+i,1,plan));db.commit();
            }
            assertEquals(9,number(db,"SELECT coins FROM towns WHERE id=1"));
            assertEquals(1,number(db,"SELECT coins FROM civilizations WHERE id=1"));
            assertEquals(20,number(db,"SELECT accumulated_culture FROM towns WHERE id=1"));
            assertEquals(10,number(db,"SELECT COUNT(*) FROM trade_ship_income_receipts"));
        }
    }
    @Test void rollbackRestoresReceiptFractionsCultureAndDebt()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("UPDATE towns SET debt=5 WHERE id=1");
            db.setAutoCommit(false);var plan=TradeShipIncomeStore.plan(db,1,10,8,rates);
            TradeShipIncomeStore.credit(db,"rollback",1,plan);db.rollback();
            assertEquals(5,number(db,"SELECT debt FROM towns WHERE id=1"));
            assertEquals(0,number(db,"SELECT COUNT(*) FROM trade_ship_income_receipts"));
            assertEquals(0,number(db,"SELECT COUNT(*) FROM trade_ship_town_fractions"));
            TradeShipIncomeStore.credit(db,"rollback",1,plan);db.commit();
            assertEquals(0,number(db,"SELECT debt FROM towns WHERE id=1"));
            assertEquals(4,number(db,"SELECT coins FROM towns WHERE id=1"));
            assertEquals(8,number(db,"SELECT accumulated_culture FROM towns WHERE id=1"));
            db.setAutoCommit(true);
            assertThrows(SQLException.class,()->TradeShipIncomeStore.credit(db,"unsafe",1,plan));
        }
    }
}
