package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UpgradeStoreTest {
    final UUID mayor=UUID.fromString("11111111-1111-1111-1111-111111111111");
    UpgradeDefinition bank=new UpgradeDefinition("bank2","Банк II",4000,"set_bank_level","2","","code","s_bank","Банк");
    UpgradeDefinition interest=new UpgradeDefinition("interest1","Проценты",2000,"set_bank_interest","0.0075","bank2","writing","s_bank","Банк");
    void schema(Connection db)throws SQLException {
        db.createStatement().execute("PRAGMA foreign_keys=ON");
        db.createStatement().executeUpdate("CREATE TABLE civilizations(id INTEGER PRIMARY KEY,leader_uuid TEXT)");
        db.createStatement().executeUpdate("CREATE TABLE towns(id INTEGER PRIMARY KEY,civ_id INTEGER,mayor_uuid TEXT,coins INTEGER,level INTEGER)");
        db.createStatement().executeUpdate("CREATE TABLE civ_technologies(civ_id INTEGER,tech_id TEXT)");
        db.createStatement().executeUpdate("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY,town_id INTEGER,structure_id TEXT,status TEXT)");
        db.createStatement().executeUpdate("CREATE TABLE town_trade_settings(town_id INTEGER PRIMARY KEY,bank_level INTEGER DEFAULT 1,bank_fee REAL DEFAULT 0.05)");
        db.createStatement().executeUpdate("INSERT INTO civilizations VALUES(1,'22222222-2222-2222-2222-222222222222')");
        db.createStatement().executeUpdate("INSERT INTO towns VALUES(1,1,'"+mayor+"',20000,1)");
        UpgradeStore.install(db);BankDailyStore.install(db);
    }
    @Test void grocerUpgradeUsesNormalAuthorityAndResearchGuardsAndPersistsLevel()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);var upgrade=new UpgradeDefinition("grocer2","Бакалея II",2500,"set_grocer_level","2","","trade","s_grocer","Бакалея");
            assertThrows(IllegalArgumentException.class,()->UpgradeStore.buy(db,1,mayor,upgrade,0));
            db.createStatement().execute("INSERT INTO civ_technologies VALUES(1,'trade')");db.createStatement().execute("INSERT INTO structure_builds VALUES(1,1,'s_grocer','COMPLETE')");
            db.setAutoCommit(false);UpgradeStore.buy(db,1,mayor,upgrade,0);db.commit();
            assertEquals(17500,coins(db));assertEquals(2,com.avrgaming.civcraft.modern.trade.GrocerStore.settings(db,1).level());
            assertThrows(IllegalArgumentException.class,()->UpgradeStore.buy(db,1,mayor,upgrade,0));db.rollback();assertEquals(17500,coins(db));
        }
    }
    @Test void validatesAuthorityTechnologyAndCompletedBuildingBeforeDebiting()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);
            assertThrows(IllegalArgumentException.class,()->UpgradeStore.buy(db,1,UUID.randomUUID(),bank,0));
            assertThrows(IllegalArgumentException.class,()->UpgradeStore.buy(db,1,mayor,bank,0));
            db.createStatement().executeUpdate("INSERT INTO civ_technologies VALUES(1,'code')");
            db.createStatement().executeUpdate("INSERT INTO structure_builds VALUES(1,1,'s_bank','QUEUED')");
            assertThrows(IllegalArgumentException.class,()->UpgradeStore.buy(db,1,mayor,bank,0));
            assertEquals(20000,coins(db));assertTrue(UpgradeStore.purchased(db,1).isEmpty());
        }
    }
    @Test void purchasesEffectsAndGuardsPreviousUpgradeAndDoublePayment()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);db.createStatement().executeUpdate("INSERT INTO civ_technologies VALUES(1,'code'),(1,'writing')");
            db.createStatement().executeUpdate("INSERT INTO structure_builds VALUES(1,1,'s_bank','COMPLETE')");
            assertThrows(IllegalArgumentException.class,()->UpgradeStore.buy(db,1,mayor,interest,0));
            db.setAutoCommit(false);UpgradeStore.buy(db,1,mayor,bank,0);db.commit();
            assertEquals(16000,coins(db));
            assertThrows(IllegalArgumentException.class,()->UpgradeStore.buy(db,1,mayor,bank,0));db.rollback();
            UpgradeStore.buy(db,1,mayor,interest,0);db.commit();
            try(var row=db.createStatement().executeQuery("SELECT bank_level FROM town_trade_settings")){assertTrue(row.next());assertEquals(2,row.getInt(1));}
            try(var row=db.createStatement().executeQuery("SELECT interest_rate,principal FROM bank_interest_accounts")){assertTrue(row.next());assertEquals(0.0075,row.getDouble(1));assertEquals(14000,row.getLong(2));}
            var town=new UpgradeDefinition("town2","Деревня",10000,"set_town_level","2","","code","","Город");
            UpgradeStore.buy(db,1,mayor,town,0);db.commit();
            assertEquals(4000,coins(db));
            try(var row=db.createStatement().executeQuery("SELECT principal FROM bank_interest_accounts")){assertTrue(row.next());assertEquals(4000,row.getLong(1));}
        }
    }
    @Test void failedTransactionRestoresUpgradeMoneyAndEffect()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);db.createStatement().executeUpdate("INSERT INTO civ_technologies VALUES(1,'code')");
            db.createStatement().executeUpdate("INSERT INTO structure_builds VALUES(1,1,'s_bank','COMPLETE')");
            db.setAutoCommit(false);UpgradeStore.buy(db,1,mayor,bank,0);db.rollback();
            assertEquals(20000,coins(db));assertTrue(UpgradeStore.purchased(db,1).isEmpty());
            try(var row=db.createStatement().executeQuery("SELECT COUNT(*) FROM town_trade_settings")){assertTrue(row.next());assertEquals(0,row.getInt(1));}
        }
    }
    long coins(Connection db)throws SQLException {try(var row=db.createStatement().executeQuery("SELECT coins FROM towns WHERE id=1")){assertTrue(row.next());return row.getLong(1);}}
    @Test void tradeShipUpgradePersistsSeparatelyFromShipProgressAndRollsBack()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);
            db.createStatement().execute("INSERT INTO structure_builds VALUES(1,1,'ti_trade_ship','COMPLETE')");
            var upgrade=new UpgradeDefinition("ship2","Корабль II",1000,"set_tradeship_upgrade_level","2","","","ti_trade_ship","Корабль");
            db.setAutoCommit(false);UpgradeStore.buy(db,1,mayor,upgrade,0);db.rollback();
            assertEquals(20000,coins(db));assertEquals(1,com.avrgaming.civcraft.modern.trade.TradeShipStore.upgrade(db,1));
            UpgradeStore.buy(db,1,mayor,upgrade,0);db.commit();
            assertEquals(19000,coins(db));assertEquals(2,com.avrgaming.civcraft.modern.trade.TradeShipStore.upgrade(db,1));
            var state=new com.avrgaming.civcraft.modern.trade.TradeShipProgress.State(3,137);
            com.avrgaming.civcraft.modern.trade.TradeShipStore.progress(db,1,state);db.commit();
            assertEquals(state,com.avrgaming.civcraft.modern.trade.TradeShipStore.progress(db,1));
            assertEquals(2,com.avrgaming.civcraft.modern.trade.TradeShipStore.upgrade(db,1));
            db.createStatement().execute("DELETE FROM structure_builds WHERE id=1");db.commit();
            assertEquals(new com.avrgaming.civcraft.modern.trade.TradeShipProgress.State(1,0),com.avrgaming.civcraft.modern.trade.TradeShipStore.progress(db,1));
            assertEquals(2,com.avrgaming.civcraft.modern.trade.TradeShipStore.upgrade(db,1));
        }
    }
}
