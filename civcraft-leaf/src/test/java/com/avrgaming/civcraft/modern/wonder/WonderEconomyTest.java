package com.avrgaming.civcraft.modern.wonder;
import java.sql.*;
import java.util.*;
import com.avrgaming.civcraft.modern.town.UpkeepStore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WonderEconomyTest {
    final WonderEconomy economy=WonderEconomy.load(new java.io.File("src/main/resources/civcraft/data/buffs.yml"));
    Connection database()throws Exception {
        var db=DriverManager.getConnection("jdbc:sqlite::memory:");try(var s=db.createStatement()) {
            s.execute("CREATE TABLE civilizations(id INTEGER PRIMARY KEY,coins INTEGER)");s.execute("INSERT INTO civilizations VALUES(1,10),(2,0)");
            s.execute("CREATE TABLE towns(id INTEGER PRIMARY KEY,civ_id INTEGER,coins INTEGER)");s.execute("INSERT INTO towns VALUES(1,1,0),(2,1,0),(3,2,0)");
            s.execute("CREATE TABLE structure_builds(id INTEGER,town_id INTEGER,structure_id TEXT,status TEXT)");s.execute("INSERT INTO structure_builds VALUES(1,1,'w_colossus','COMPLETE'),(2,2,'w_colosseum','COMPLETE')");
            s.execute("CREATE TABLE culture_regions(town_id INTEGER)");s.execute("INSERT INTO culture_regions VALUES(1),(1),(2),(3)");
        }UpkeepStore.install(db);WonderIncomeStore.install(db);return db;
    }
    long value(Connection db,String column)throws Exception {try(var r=db.createStatement().executeQuery("SELECT "+column+" FROM civilizations WHERE id=1")){r.next();return r.getLong(1);}}
    @Test void colossusDiscountOnlyAffectsTheCompletedHostTown()throws Exception {try(var db=database()){assertEquals(75,economy.upkeep(db,1,100));assertEquals(100,economy.upkeep(db,2,100));db.createStatement().execute("UPDATE structure_builds SET status='BUILDING' WHERE id=1");assertEquals(100,economy.upkeep(db,1,100));}}
    @Test void dailyIncomeUsesCivilizationCultureAndAllWorldTownsExactlyOnce()throws Exception {try(var db=database()){var paid=WonderIncomeStore.pay(db,"2026-10-14",0,economy);assertEquals(15,paid.get(0).coins());assertEquals(3,paid.get(0).units());assertEquals(300,paid.get(1).coins());assertEquals(325,value(db,"coins"));assertEquals(0,value(db,"debt"));assertTrue(WonderIncomeStore.pay(db,"2026-10-14",1,economy).isEmpty());assertEquals(325,value(db,"coins"));}}
    @Test void dailyIncomeStartsDuringConstructionAsOriginalDailyTimerAndStopsOnRemoval()throws Exception {try(var db=database()){db.createStatement().execute("UPDATE structure_builds SET status='QUEUED'");assertEquals(2,WonderIncomeStore.pay(db,"day1",0,economy).size());db.createStatement().execute("UPDATE structure_builds SET status='WAR_DESTROYED'");assertTrue(WonderIncomeStore.pay(db,"day2",0,economy).isEmpty());}}
    @Test void payoutsFollowTheCurrentOwnerAndRepayTheirDebtFirst()throws Exception {try(var db=database()){db.createStatement().execute("UPDATE towns SET civ_id=2 WHERE id=1");db.createStatement().execute("UPDATE civilizations SET debt=7 WHERE id=2");var paid=WonderIncomeStore.pay(db,"day1",0,economy);assertEquals(2,paid.get(0).civ());assertEquals(15,paid.get(0).coins());try(var r=db.createStatement().executeQuery("SELECT coins,debt FROM civilizations WHERE id=2")){r.next();assertEquals(8,r.getLong(1));assertEquals(0,r.getLong(2));}}}
    @Test void rollbackRestoresTreasuryAndDayMarkerTogether()throws Exception {try(var db=database()){db.setAutoCommit(false);WonderIncomeStore.pay(db,"day1",0,economy);db.rollback();assertEquals(10,value(db,"coins"));assertEquals(2,WonderIncomeStore.pay(db,"day1",1,economy).size());db.commit();}}
    @Test void wonderCreditsOccurAfterTownCostsAndBeforeCivilizationCosts()throws Exception {try(var db=database()){db.createStatement().execute("UPDATE civilizations SET coins=0 WHERE id=1");var payments=UpkeepStore.daily(db,"day1",0,List.of(new UpkeepStore.Charge(1,1,true,100)),Map.of(1L,200L),()->WonderIncomeStore.pay(db,"day1",0,economy));assertEquals(200,payments.get(1).paid());assertEquals(15,value(db,"coins"));assertEquals(0,value(db,"debt"));assertTrue(UpkeepStore.daily(db,"day1",1,List.of(),Map.of(),()->{throw new AssertionError();}).isEmpty());}}
    @Test void persistedIncomeAuditPreventsPayingAgainAfterReopen(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)throws Exception {var file=directory.resolve("income.db");try(var db=database()){WonderIncomeStore.pay(db,"day1",0,economy);assertEquals(0,db.unwrap(org.sqlite.SQLiteConnection.class).getDatabase().backup("main",file.toString(),null));}try(var db=DriverManager.getConnection("jdbc:sqlite:"+file)){assertTrue(WonderIncomeStore.pay(db,"day1",1,economy).isEmpty());assertEquals(325,value(db,"coins"));}}
}
