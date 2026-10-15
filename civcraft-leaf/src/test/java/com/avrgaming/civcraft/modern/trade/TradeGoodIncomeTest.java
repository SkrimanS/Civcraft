package com.avrgaming.civcraft.modern.trade;

import java.io.File;
import java.math.BigDecimal;
import java.sql.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TradeGoodIncomeTest {
    @TempDir Path directory;
    final TradeGoodIncome income=new TradeGoodIncome(new File("src/main/resources/civcraft/data"));
    static void decimal(String expected,BigDecimal actual){assertEquals(0,new BigDecimal(expected).compareTo(actual));}
    @Test void duplicateMultiplierAppliesToEveryPayingInstanceAndCapsExtraCopiesAtThree() {
        decimal("250",income.base(List.of("good_pelts"),Map.of("good_pelts",1)));
        decimal("750",income.base(List.of("good_pelts","good_pelts"),Map.of("good_pelts",2)));
        decimal("2500",income.base(Collections.nCopies(4,"good_pelts"),Map.of("good_pelts",4)));
        decimal("5000",income.base(Collections.nCopies(8,"good_pelts"),Map.of("good_pelts",8)));
        decimal("625",income.base(List.of("good_pelts"),Map.of("good_pelts",8)));
    }
    @Test void marketAddsBeforeMonopolyAndHappinessAndTaxPreservesFractions() {
        assertEquals(.1,income.structureRate("s_market"));assertEquals(0,income.structureRate("s_bank"));
        var payment=TradeGoodIncome.payment(new BigDecimal("100"),1,income.structureRate("s_market"),.3,1.2,.1);
        decimal("154.44",payment.town());decimal("17.16",payment.civilization());assertEquals(0,payment.culture());
        decimal("0",TradeGoodIncome.payment(new BigDecimal("100"),0,0,1,1,.2).town());
        assertThrows(IllegalArgumentException.class,()->TradeGoodIncome.payment(BigDecimal.ONE,Double.NaN,0,0,1,0));
        assertThrows(IllegalArgumentException.class,()->TradeGoodIncome.payment(BigDecimal.ONE,1,0,0,1,1.01));
    }
    void schema(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("CREATE TABLE civilizations(id INTEGER PRIMARY KEY,coins INTEGER DEFAULT 0,debt INTEGER DEFAULT 0,days_in_debt INTEGER DEFAULT 0)");
            s.execute("CREATE TABLE towns(id INTEGER PRIMARY KEY,civ_id INTEGER,coins INTEGER DEFAULT 0,debt INTEGER DEFAULT 0,days_in_debt INTEGER DEFAULT 0,accumulated_culture INTEGER DEFAULT 0)");
            s.execute("INSERT INTO civilizations(id) VALUES(1),(2)");s.execute("INSERT INTO towns(id,civ_id) VALUES(1,1),(2,2)");
            s.execute("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY,town_id INTEGER,structure_id TEXT,status TEXT,max_hitpoints INTEGER,world TEXT,x INTEGER,z INTEGER)");
            s.execute("INSERT INTO structure_builds VALUES(1,1,'ti_trade_outpost','COMPLETE',100,'world',-1,-17),(2,2,'s_townhall','COMPLETE',100,'world',100,100)");
            s.execute("CREATE TABLE goodie_frames(frame_key TEXT PRIMARY KEY,build_id INTEGER,is_source INTEGER)");s.execute("INSERT INTO goodie_frames VALUES('2:0',2,0),('1:-1',1,1)");
            s.execute("CREATE TABLE bonus_goodies(origin_build INTEGER,good_id TEXT,holder_kind TEXT,holder_id TEXT,host_town INTEGER)");s.execute("INSERT INTO bonus_goodies VALUES(1,'good_pelts','FRAME','2:0',2)");
            s.execute("CREATE TABLE goodie_transfers(origin_build INTEGER,status TEXT)");
            s.execute("CREATE TABLE building_combat_health(build_id INTEGER,hitpoints INTEGER)");
            s.execute("CREATE TABLE culture_regions(world TEXT,chunk_x INTEGER,chunk_z INTEGER,town_id INTEGER)");
        }
        TradeGoodIncomeStore.install(db);
    }
    long number(Connection db,String query)throws SQLException {try(var s=db.createStatement();var r=s.executeQuery(query)){assertTrue(r.next());return r.getLong(1);}}
    @Test void sourceMustBeInAnyCultureAndAliveAndHallFrameMustMatchIncludingNegativeChunks()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);assertTrue(TradeGoodIncomeStore.payable(db,2).isEmpty());
            db.createStatement().execute("INSERT INTO culture_regions VALUES('world',-1,-2,1)");
            assertEquals(List.of("good_pelts"),TradeGoodIncomeStore.payable(db,2));assertTrue(TradeGoodIncomeStore.payable(db,1).isEmpty());
            db.createStatement().execute("INSERT INTO goodie_transfers VALUES(1,'PREPARED')");assertTrue(TradeGoodIncomeStore.payable(db,2).isEmpty());
            db.createStatement().execute("UPDATE goodie_transfers SET status='COMPLETE'");assertEquals(List.of("good_pelts"),TradeGoodIncomeStore.payable(db,2));
            db.createStatement().execute("INSERT INTO building_combat_health VALUES(1,0)");assertTrue(TradeGoodIncomeStore.payable(db,2).isEmpty());
            db.createStatement().execute("DELETE FROM building_combat_health");db.createStatement().execute("UPDATE structure_builds SET status='WAR_DESTROYED' WHERE id=2");assertTrue(TradeGoodIncomeStore.payable(db,2).isEmpty());
            db.createStatement().execute("UPDATE structure_builds SET status='COMPLETE' WHERE id=2");db.createStatement().execute("UPDATE bonus_goodies SET holder_kind='PLAYER'");assertTrue(TradeGoodIncomeStore.payable(db,2).isEmpty());
            db.createStatement().execute("UPDATE bonus_goodies SET holder_kind='FRAME',holder_id='1:-1'");assertTrue(TradeGoodIncomeStore.payable(db,2).isEmpty());
        }
    }
    @Test void rollbackAndReplayPreserveWalletsFractionsAndClockTogether()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);TradeGoodIncomeStore.start(db,3600000,10);db.setAutoCommit(false);
            var payment=TradeGoodIncome.payment(BigDecimal.ONE,1,0,0,1,.1);
            assertTrue(TradeGoodIncomeStore.begin(db,3600000,11));TradeGoodIncomeStore.credit(db,3600000,11,2,2,payment);db.rollback();
            assertEquals(0,number(db,"SELECT COUNT(*) FROM goodie_income_hours"));assertEquals(10,number(db,"SELECT last_hour FROM goodie_income_clock"));
            assertTrue(TradeGoodIncomeStore.begin(db,3600000,11));assertTrue(TradeGoodIncomeStore.credit(db,3600000,11,2,2,payment));db.commit();
            assertFalse(TradeGoodIncomeStore.begin(db,3600000,11));assertFalse(TradeGoodIncomeStore.credit(db,3600000,11,2,2,payment));db.commit();
            for(int hour=12;hour<21;hour++){assertTrue(TradeGoodIncomeStore.begin(db,3600000,hour));TradeGoodIncomeStore.credit(db,3600000,hour,2,2,payment);db.commit();}
            assertEquals(9,number(db,"SELECT coins FROM towns WHERE id=2"));assertEquals(1,number(db,"SELECT coins FROM civilizations WHERE id=2"));
        }
    }
    @Test void goodieAndShipFractionsCombineAndPayExistingDebt()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);db.createStatement().execute("UPDATE towns SET debt=1 WHERE id=2");db.setAutoCommit(false);
            var payment=new TradeShipIncome.Payment(new BigDecimal("0.6"),new BigDecimal("0.6"),0);
            TradeGoodIncomeStore.credit(db,3600000,11,2,2,payment);
            TradeShipIncomeStore.credit(db,"ship-receipt",2,new TradeShipIncomeStore.Plan(2,payment));db.commit();
            assertEquals(0,number(db,"SELECT debt FROM towns WHERE id=2"));assertEquals(0,number(db,"SELECT coins FROM towns WHERE id=2"));assertEquals(1,number(db,"SELECT coins FROM civilizations WHERE id=2"));
            try(var s=db.createStatement();var r=s.executeQuery("SELECT amount FROM trade_ship_town_fractions WHERE town_id=2")){assertTrue(r.next());decimal("0.2",new BigDecimal(r.getString(1)));}
        }
    }
    @Test void restartSkipsOfflineHoursAndDoesNotReplayTheLastPaidBoundary()throws Exception {
        String url="jdbc:sqlite:"+directory.resolve("income.sqlite");
        try(var db=DriverManager.getConnection(url)){schema(db);TradeGoodIncomeStore.start(db,3600000,10);db.setAutoCommit(false);assertTrue(TradeGoodIncomeStore.begin(db,3600000,11));db.commit();}
        try(var db=DriverManager.getConnection(url)){TradeGoodIncomeStore.start(db,3600000,15);db.setAutoCommit(false);assertFalse(TradeGoodIncomeStore.begin(db,3600000,15));assertTrue(TradeGoodIncomeStore.begin(db,3600000,16));db.commit();}
    }
    @Test void warConsumesTheBoundaryWithoutPayingItLaterInTheSameHour()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);com.avrgaming.civcraft.modern.war.WarClockStore.install(db);TradeGoodIncomeStore.start(db,1000,10);
            db.createStatement().execute("INSERT INTO war_sessions VALUES(11000,11500,'ACTIVE')");db.setAutoCommit(false);
            assertFalse(TradeGoodIncomeStore.boundary(db,1000,11,11100));db.commit();
            assertFalse(TradeGoodIncomeStore.boundary(db,1000,11,11600));assertTrue(TradeGoodIncomeStore.boundary(db,1000,12,12100));db.commit();
            assertEquals(0,number(db,"SELECT COUNT(*) FROM goodie_income_hours"));
        }
    }
    @Test void onlyCompletedLivingMarketStructuresAddTheirRate()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);db.createStatement().execute("INSERT INTO structure_builds(id,town_id,structure_id,status,max_hitpoints) VALUES(3,2,'s_market','COMPLETE',100),(4,2,'s_market','BUILDING',100)");
            assertEquals(.1,TradeGoodIncomeStore.structureRate(db,2,income));
            db.createStatement().execute("INSERT INTO building_combat_health VALUES(3,0)");assertEquals(0,TradeGoodIncomeStore.structureRate(db,2,income));
            db.createStatement().execute("UPDATE building_combat_health SET hitpoints=100");db.createStatement().execute("UPDATE structure_builds SET status='DEMOLISHED' WHERE id=3");assertEquals(0,TradeGoodIncomeStore.structureRate(db,2,income));
        }
    }
}
