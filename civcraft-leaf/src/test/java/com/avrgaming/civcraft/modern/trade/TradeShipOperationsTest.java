package com.avrgaming.civcraft.modern.trade;

import java.sql.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TradeShipOperationsTest {
    @TempDir Path directory;
    void schema(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("CREATE TABLE civilizations(id INTEGER PRIMARY KEY,coins INTEGER DEFAULT 0,debt INTEGER DEFAULT 0,days_in_debt INTEGER DEFAULT 0,income_tax_rate REAL DEFAULT 0.1)");
            s.execute("CREATE TABLE towns(id INTEGER PRIMARY KEY,civ_id INTEGER,coins INTEGER DEFAULT 0,debt INTEGER DEFAULT 0,days_in_debt INTEGER DEFAULT 0,accumulated_culture INTEGER DEFAULT 0)");
            s.execute("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY,town_id INTEGER,structure_id TEXT,status TEXT,max_hitpoints INTEGER DEFAULT 100)");
            s.execute("CREATE TABLE building_combat_health(build_id INTEGER,hitpoints INTEGER,maximum INTEGER DEFAULT 100,phase TEXT DEFAULT 'DAMAGED')");
            s.execute("INSERT INTO civilizations(id) VALUES(1)");s.execute("INSERT INTO towns(id,civ_id) VALUES(1,1)");
            s.execute("INSERT INTO structure_builds(id,town_id,structure_id,status) VALUES(1,1,'ti_trade_ship','COMPLETE')");
        }
        TradeShipOperations.install(db);db.setAutoCommit(false);
    }
    TradeShipProgress.Outcome outcome() {
        return TradeShipProgress.consume(Map.of(1,new TradeShipProgress.Level(1,1024,2,.1)),
                new TradeShipProgress.State(1,0),1,List.of(new TradeShipProgress.Cargo(10,true,1)));
    }
    TradeShipIncomeStore.Plan payment(Connection db)throws SQLException {
        return TradeShipIncomeStore.plan(db,1,10,1,new TradeShipIncome.Rates(1.3,1.2,1.2));
    }
    long number(Connection db,String sql)throws SQLException {try(var s=db.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
    @Test void committedIntentSurvivesDatabaseReopenAndPaysOnlyOnce()throws Exception {
        String url="jdbc:sqlite:"+directory.resolve("ships.sqlite");
        try(var db=DriverManager.getConnection(url)) {
            schema(db);var before=TradeShipOperations.initialize(db,1,3600000,20);
            TradeShipOperations.prepare(db,"receipt",before,21,outcome(),payment(db),new byte[]{1,2,3});db.commit();
            assertEquals(0,number(db,"SELECT coins FROM towns"));
        }
        try(var db=DriverManager.getConnection(url)) {
            db.setAutoCommit(false);var pending=TradeShipOperations.pending(db).getFirst();
            assertArrayEquals(new byte[]{1,2,3},pending.containers());
            assertThrows(SQLException.class,()->TradeShipOperations.start(db,3600000,30));
            assertTrue(TradeShipOperations.complete(db,pending.id()));db.commit();
            assertFalse(TradeShipOperations.complete(db,pending.id()));db.commit();
            assertEquals(9,number(db,"SELECT coins FROM towns"));assertEquals(1,number(db,"SELECT coins FROM civilizations"));
            assertEquals(new TradeShipProgress.State(1,10),TradeShipStore.progress(db,1));
            assertTrue(TradeShipOperations.pending(db).isEmpty());
            assertEquals(0,number(db,"SELECT length(containers) FROM trade_ship_operations"));
            TradeShipOperations.start(db,3600000,30);db.commit();assertEquals(30,TradeShipOperations.clock(db,1).hour());
        }
    }
    @Test void onePendingOperationAndUnchangedProgressAreRequired()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);var before=TradeShipOperations.initialize(db,1,3600000,20);db.commit();
            assertThrows(IllegalArgumentException.class,()->TradeShipOperations.prepare(db,"old",before,20,outcome(),payment(db),new byte[]{1}));
            TradeShipOperations.prepare(db,"one",before,21,outcome(),payment(db),new byte[]{1});db.commit();
            assertThrows(SQLException.class,()->TradeShipOperations.prepare(db,"two",before,22,outcome(),payment(db),new byte[]{1}));db.rollback();
            TradeShipStore.progress(db,1,new TradeShipProgress.State(1,5));
            assertThrows(SQLException.class,()->TradeShipOperations.complete(db,"one"));db.rollback();
            assertEquals(1,TradeShipOperations.pending(db).size());assertEquals(0,number(db,"SELECT coins FROM towns"));
        }
    }
    @Test void completionRollbackLeavesIntentAndClockAvailableForRecovery()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);var before=TradeShipOperations.initialize(db,1,3600000,20);
            TradeShipOperations.prepare(db,"one",before,21,outcome(),payment(db),new byte[]{1});db.commit();
            TradeShipOperations.complete(db,"one");db.rollback();
            assertEquals(before,TradeShipOperations.clock(db,1));assertEquals(1,TradeShipOperations.pending(db).size());
            assertEquals(0,number(db,"SELECT COUNT(*) FROM trade_ship_income_receipts"));assertEquals(0,number(db,"SELECT accumulated_culture FROM towns"));
            TradeShipOperations.complete(db,"one");db.commit();assertEquals(1,number(db,"SELECT accumulated_culture FROM towns"));
        }
    }
    @Test void destructionWaitsForTradeThenResetsEarnedLevelWithoutRemovingPurchasedUpgrade()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);com.avrgaming.civcraft.modern.war.BuildingRuinStore.install(db);
            TradeShipStore.upgrade(db,1,3);TradeShipStore.progress(db,1,new TradeShipProgress.State(5,200));
            var before=TradeShipOperations.initialize(db,1,3600000,20);
            var sale=new TradeShipProgress.Outcome(new TradeShipProgress.State(5,210),TradeShipProgress.Result.GROW,List.of(0),10,10,1);
            TradeShipOperations.prepare(db,"sale",before,21,sale,payment(db),new byte[]{1});db.commit();
            db.createStatement().execute("UPDATE structure_builds SET status='WAR_DESTROY_PENDING' WHERE id=1");
            db.createStatement().execute("INSERT INTO building_combat_health VALUES(1,0,100,'DESTROY_PENDING')");db.commit();
            assertThrows(IllegalArgumentException.class,()->TradeShipOperations.requireTownUnlocked(db,1));
            assertDoesNotThrow(()->TradeShipOperations.requireTownUnlocked(db,2));
            assertThrows(IllegalArgumentException.class,()->com.avrgaming.civcraft.modern.war.BuildingRuinStore.prepare(db,1,List.of()));
            assertEquals(0,number(db,"SELECT COUNT(*) FROM building_ruin_jobs"));
            TradeShipOperations.complete(db,"sale");db.commit();
            assertDoesNotThrow(()->TradeShipOperations.requireTownUnlocked(db,1));
            com.avrgaming.civcraft.modern.war.BuildingRuinStore.prepare(db,1,List.of());db.commit();
            com.avrgaming.civcraft.modern.war.BuildingRuinStore.complete(db,1);db.rollback();
            assertEquals(new TradeShipProgress.State(5,210),TradeShipStore.progress(db,1));
            assertTrue(com.avrgaming.civcraft.modern.war.BuildingRuinStore.complete(db,1));db.commit();
            assertEquals(new TradeShipProgress.State(1,0),TradeShipStore.progress(db,1));assertEquals(3,TradeShipStore.upgrade(db,1));
            assertEquals(9,number(db,"SELECT coins FROM towns"));assertEquals(21,TradeShipOperations.clock(db,1).hour());
        }
    }
}
