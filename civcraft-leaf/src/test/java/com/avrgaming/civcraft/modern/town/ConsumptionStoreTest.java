package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsumptionStoreTest {
    Connection database()throws Exception {
        var db=DriverManager.getConnection("jdbc:sqlite::memory:");
        try(var s=db.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("CREATE TABLE civilizations(id INTEGER PRIMARY KEY,coins INTEGER,debt INTEGER,days_in_debt INTEGER)");
            s.execute("CREATE TABLE towns(id INTEGER PRIMARY KEY,civ_id INTEGER,coins INTEGER,debt INTEGER,days_in_debt INTEGER,accumulated_culture INTEGER)");
            s.execute("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY,town_id INTEGER,status TEXT)");
            s.execute("INSERT INTO civilizations VALUES(1,100,0,0)");
            s.execute("INSERT INTO towns VALUES(1,1,100,0,0,0)");
            s.execute("INSERT INTO structure_builds VALUES(1,1,'COMPLETE')");
        }
        ConsumptionStore.install(db);db.setAutoCommit(false);
        ConsumptionStore.initialize(db,1,3600000,10);db.commit();return db;
    }
    ConsumptionStore.Pending prepare(Connection db,String id,long hour)throws Exception {
        return ConsumptionStore.prepare(db,id,ConsumptionStore.state(db,1),
            new ConsumptionRules.State(1,1,ConsumptionRules.Result.GROW,true),hour,80,20,100,new byte[]{1,2,3});
    }
    long number(Connection db,String sql)throws Exception {
        try(var s=db.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}
    }
    @Test void preparePersistsIntentWithoutPayoutOrStateAdvanceThenRecoveryPaysOnce()throws Exception {
        try(var db=database()) {
            prepare(db,"food-1",11);db.commit();
            assertEquals(0,ConsumptionStore.state(db,1).count());assertEquals(100,number(db,"SELECT coins FROM towns"));
            var intent=ConsumptionStore.pending(db).getFirst();assertArrayEquals(new byte[]{1,2,3},intent.containers());
            assertTrue(ConsumptionStore.complete(db,intent.id()));db.commit();
            assertFalse(ConsumptionStore.complete(db,intent.id()));db.commit();
            assertEquals(1,ConsumptionStore.state(db,1).count());assertEquals(11,ConsumptionStore.state(db,1).hour());
            assertEquals(180,number(db,"SELECT coins FROM towns"));assertEquals(120,number(db,"SELECT coins FROM civilizations"));
            assertEquals(100,number(db,"SELECT accumulated_culture FROM towns"));
            assertEquals(0,number(db,"SELECT length(containers) FROM consumption_receipts"));
            assertTrue(ConsumptionStore.pending(db).isEmpty());
        }
    }
    @Test void onePendingIntentPerBuildingPreventsRepeatedFoodDebit()throws Exception {
        try(var db=database()) {
            prepare(db,"first",11);db.commit();
            assertThrows(SQLException.class,()->prepare(db,"second",12));db.rollback();
            assertEquals(1,ConsumptionStore.pending(db).size());
            assertThrows(SQLException.class,()->ConsumptionStore.start(db,3600000,40));db.rollback();
        }
    }
    @Test void restartSkipsOfflineTimeWithoutErasingLevelAndRejectsOldPlans()throws Exception {
        try(var db=database()) {
            var stale=ConsumptionStore.state(db,1);
            prepare(db,"first",11);db.commit();ConsumptionStore.complete(db,"first");db.commit();
            ConsumptionStore.start(db,3600000,40);db.commit();
            assertEquals(1,ConsumptionStore.state(db,1).count());assertEquals(40,ConsumptionStore.state(db,1).hour());
            assertThrows(SQLException.class,()->ConsumptionStore.prepare(db,"stale",stale,
                new ConsumptionRules.State(1,1,ConsumptionRules.Result.GROW,true),41,0,0,100,new byte[]{1}));db.rollback();
            ConsumptionStore.initialize(db,1,3600000,100);db.commit();assertEquals(40,ConsumptionStore.state(db,1).hour());
        }
    }
    @Test void lateFailureRollsBackAllPayoutsAndStateLeavingReceiptRecoverable()throws Exception {
        try(var db=database()) {
            prepare(db,"first",11);db.commit();
            db.createStatement().execute("UPDATE towns SET accumulated_culture=9223372036854775807");db.commit();
            assertThrows(SQLException.class,()->ConsumptionStore.complete(db,"first"));db.rollback();
            assertEquals(100,number(db,"SELECT coins FROM towns"));assertEquals(100,number(db,"SELECT coins FROM civilizations"));
            assertEquals(0,ConsumptionStore.state(db,1).count());assertEquals(1,ConsumptionStore.pending(db).size());
            db.createStatement().execute("UPDATE towns SET accumulated_culture=0");db.commit();
            assertTrue(ConsumptionStore.complete(db,"first"));db.commit();assertEquals(100,number(db,"SELECT accumulated_culture FROM towns"));
        }
    }
    @Test void buildingIncomeRepaysTreasuryDebtBeforeCashAndStarvationCannotEarn()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("UPDATE towns SET debt=60,days_in_debt=2");db.commit();
            prepare(db,"first",11);db.commit();ConsumptionStore.complete(db,"first");db.commit();
            assertEquals(0,number(db,"SELECT debt FROM towns"));assertEquals(0,number(db,"SELECT days_in_debt FROM towns"));
            assertEquals(120,number(db,"SELECT coins FROM towns"));
            assertThrows(IllegalArgumentException.class,()->ConsumptionStore.prepare(db,"starve",ConsumptionStore.state(db,1),
                new ConsumptionRules.State(1,0,ConsumptionRules.Result.STARVE,false),12,1,0,0,new byte[]{1}));
        }
    }
    @Test void overlappingPoisonRetainsLongestDurationAndRecoveryDoesNotSpendAnotherTick()throws Exception {
        try(var db=database()) {
            GranaryPoisonStore.apply(db,1,3);GranaryPoisonStore.apply(db,1,2);db.commit();
            assertEquals(3,GranaryPoisonStore.remaining(db,1));
            var intent=ConsumptionStore.prepare(db,"poison",ConsumptionStore.state(db,1),
                new ConsumptionRules.State(1,1,ConsumptionRules.Result.GROW,true),11,0,0,0,true,new byte[]{1,2});db.commit();
            assertTrue(intent.poisoned());assertTrue(ConsumptionStore.pending(db).getFirst().poisoned());
            assertEquals(2,GranaryPoisonStore.remaining(db,1));
            ConsumptionStore.complete(db,"poison");db.commit();assertFalse(ConsumptionStore.complete(db,"poison"));db.commit();
            assertEquals(2,GranaryPoisonStore.remaining(db,1));assertEquals(1,ConsumptionStore.state(db,1).count());
            assertEquals(100,number(db,"SELECT coins FROM towns"));assertEquals(100,number(db,"SELECT coins FROM civilizations"));
            assertEquals(0,number(db,"SELECT accumulated_culture FROM towns"));
        }
    }
    @Test void poisonReservationRollsBackWithReceiptAndMealFailure()throws Exception {
        try(var db=database()) {
            GranaryPoisonStore.apply(db,1,2);db.commit();
            ConsumptionStore.prepare(db,"poison",ConsumptionStore.state(db,1),
                new ConsumptionRules.State(1,1,ConsumptionRules.Result.GROW,true),11,0,0,0,true,new byte[]{1});
            db.rollback();assertEquals(2,GranaryPoisonStore.remaining(db,1));assertTrue(ConsumptionStore.pending(db).isEmpty());
            assertThrows(IllegalArgumentException.class,()->ConsumptionStore.prepare(db,"paid-poison",ConsumptionStore.state(db,1),
                new ConsumptionRules.State(1,1,ConsumptionRules.Result.GROW,true),11,1,0,0,true,new byte[]{1}));db.rollback();
            assertEquals(2,GranaryPoisonStore.remaining(db,1));
        }
    }
    @Test void hungryCottageDoesNotUsePoisonAndLastSuccessfulMealExhaustsIt()throws Exception {
        try(var db=database()) {
            GranaryPoisonStore.apply(db,1,1);db.commit();
            ConsumptionStore.prepare(db,"hungry",ConsumptionStore.state(db,1),
                new ConsumptionRules.State(1,0,ConsumptionRules.Result.STAGNATE,false),11,0,0,0,false,new byte[]{1});db.commit();
            ConsumptionStore.complete(db,"hungry");db.commit();assertEquals(1,GranaryPoisonStore.remaining(db,1));
            ConsumptionStore.prepare(db,"poison",ConsumptionStore.state(db,1),
                new ConsumptionRules.State(1,1,ConsumptionRules.Result.GROW,true),12,0,0,0,true,new byte[]{1});db.commit();
            ConsumptionStore.complete(db,"poison");db.commit();assertEquals(0,GranaryPoisonStore.remaining(db,1));
            assertThrows(SQLException.class,()->ConsumptionStore.prepare(db,"expired",ConsumptionStore.state(db,1),
                new ConsumptionRules.State(1,2,ConsumptionRules.Result.GROW,true),13,0,0,0,true,new byte[]{1}));db.rollback();
            assertTrue(ConsumptionStore.pending(db).isEmpty());
        }
    }
    @Test void poisonSchemaUpgradeAndReinstallationPreserveEarlierReceipts()throws Exception {
        try(var db=database()) {
            prepare(db,"old",11);db.commit();ConsumptionStore.complete(db,"old");db.commit();
            db.createStatement().execute("ALTER TABLE consumption_receipts DROP COLUMN poisoned");db.commit();
            ConsumptionStore.install(db);db.commit();assertFalse(ConsumptionStore.complete(db,"old"));
            assertEquals(180,number(db,"SELECT coins FROM towns"));assertEquals(1,ConsumptionStore.state(db,1).count());
            assertThrows(IllegalArgumentException.class,()->GranaryPoisonStore.apply(db,1,0));
        }
    }
    @Test void cancelledBuildingCannotPrepareAndPendingDeletionDoesNotLoseInventoryIntent()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("UPDATE structure_builds SET status='CANCELLED'");db.commit();
            assertThrows(SQLException.class,()->prepare(db,"bad",11));db.rollback();
            db.createStatement().execute("UPDATE structure_builds SET status='COMPLETE'");db.commit();
            prepare(db,"first",11);db.commit();
            db.createStatement().execute("DELETE FROM structure_builds");db.commit();
            assertEquals(1,ConsumptionStore.pending(db).size());assertNull(ConsumptionStore.state(db,1));
            assertThrows(SQLException.class,()->ConsumptionStore.complete(db,"first"));db.rollback();
            assertEquals(100,number(db,"SELECT coins FROM towns"));
        }
    }
}
