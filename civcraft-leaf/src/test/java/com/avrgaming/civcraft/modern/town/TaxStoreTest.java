package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaxStoreTest {
    final UUID mayor=UUID.randomUUID(),member=UUID.randomUUID(),assistant=UUID.randomUUID();
    Connection database()throws SQLException {
        var db=DriverManager.getConnection("jdbc:sqlite::memory:");
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE residents(uuid TEXT PRIMARY KEY,coins INTEGER,town_id INTEGER,civ_id INTEGER,updated_at INTEGER)");
            s.executeUpdate("CREATE TABLE towns(id INTEGER PRIMARY KEY,civ_id INTEGER,mayor_uuid TEXT,coins INTEGER)");
            s.executeUpdate("CREATE TABLE civilizations(id INTEGER PRIMARY KEY,coins INTEGER)");
            s.executeUpdate("CREATE TABLE town_claims(id INTEGER PRIMARY KEY,town_id INTEGER)");
            s.executeUpdate("CREATE TABLE settlement_invites(target_uuid TEXT)");
            s.executeUpdate("CREATE TABLE daily_economy_events(event TEXT,day TEXT,processed_at INTEGER,PRIMARY KEY(event,day))");
            s.executeUpdate("INSERT INTO civilizations VALUES(1,0)");
        }
        try(var s=db.prepareStatement("INSERT INTO towns VALUES(1,1,?,0)")){s.setString(1,mayor.toString());s.executeUpdate();}
        for(var uuid:new UUID[]{mayor,member,assistant})try(var s=db.prepareStatement("INSERT INTO residents VALUES(?,1000,1,1,0)")){s.setString(1,uuid.toString());s.executeUpdate();}
        TaxStore.install(db);
        try(var s=db.prepareStatement("INSERT INTO town_assistants VALUES(1,?)")){s.setString(1,assistant.toString());s.executeUpdate();}
        return db;
    }
    long number(Connection db,String query)throws SQLException {try(var s=db.createStatement();var r=s.executeQuery(query)){assertTrue(r.next());return r.getLong(1);}}
    long resident(Connection db,String column)throws SQLException{return number(db,"SELECT "+column+" FROM residents WHERE uuid='"+member+"'");}
    void taxes(Connection db,long flat,double property,double income)throws SQLException {
        try(var s=db.createStatement()){s.executeUpdate("UPDATE towns SET flat_tax="+flat+",property_tax_rate="+property);s.executeUpdate("UPDATE civilizations SET income_tax_rate="+income);}
    }
    @Test void taxesUsePurchasedPlotValueAndExemptOfficialsWithoutCreatingMoney()throws Exception {
        try(var db=database()) {
            taxes(db,100,.1,.2);
            try(var s=db.prepareStatement("INSERT INTO town_claims(id,town_id,owner_uuid,purchase_value) VALUES(1,1,?,500)")){s.setString(1,member.toString());s.executeUpdate();}
            db.setAutoCommit(false);assertTrue(TaxStore.collect(db,"d1",0));db.commit();
            assertEquals(850,resident(db,"coins"));
            assertEquals(120,number(db,"SELECT coins FROM towns"));assertEquals(30,number(db,"SELECT coins FROM civilizations"));
            assertEquals(2000,number(db,"SELECT SUM(coins) FROM residents WHERE uuid<>'"+member+"'"));
            assertFalse(TaxStore.collect(db,"d1",1));db.commit();assertEquals(850,resident(db,"coins"));
        }
    }
    @Test void unpaidTaxStartsThreeCalculationGraceAndEvictsWithPlotsRepossessed()throws Exception {
        try(var db=database()) {
            taxes(db,1500,0,0);
            try(var s=db.prepareStatement("INSERT INTO town_claims(id,town_id,owner_uuid) VALUES(1,1,?)")){s.setString(1,member.toString());s.executeUpdate();}
            db.setAutoCommit(false);
            for(int i=1;i<=3;i++) {
                TaxStore.collect(db,"d"+i,0);var notices=TaxStore.advanceGrace(db,"d"+i,0);db.commit();
                assertEquals(1,notices.size());assertEquals(i==3,notices.getFirst().evicted());
                assertTrue(TaxStore.advanceGrace(db,"d"+i,1).isEmpty());db.commit();
                if(i==1){assertEquals(500,resident(db,"debt"));assertEquals(2,resident(db,"days_til_evict"));}
            }
            assertEquals(0,resident(db,"debt"));assertEquals(0,number(db,"SELECT COUNT(*) FROM residents WHERE uuid='"+member+"' AND town_id IS NOT NULL"));
            assertEquals(1,resident(db,"civ_id"));assertEquals(0,number(db,"SELECT COUNT(*) FROM town_claims WHERE owner_uuid IS NOT NULL"));
            assertEquals(1000,number(db,"SELECT coins FROM towns"));
        }
    }
    @Test void fullDebtPaymentStopsEvictionAndCreditsTownOnce()throws Exception {
        try(var db=database()) {
            taxes(db,1500,0,.2);db.setAutoCommit(false);TaxStore.collect(db,"d1",0);TaxStore.advanceGrace(db,"d1",0);db.commit();
            assertThrows(IllegalArgumentException.class,()->TaxStore.payDebt(db,member));db.rollback();
            db.createStatement().executeUpdate("UPDATE residents SET coins=700 WHERE uuid='"+member+"'");db.commit();
            assertEquals(500,TaxStore.payDebt(db,member));db.commit();
            assertEquals(200,resident(db,"coins"));assertEquals(0,resident(db,"debt"));
            assertEquals(1300,number(db,"SELECT coins FROM towns"));assertEquals(200,number(db,"SELECT coins FROM civilizations"));
            assertTrue(TaxStore.advanceGrace(db,"d2",0).isEmpty());db.commit();
            assertThrows(IllegalArgumentException.class,()->TaxStore.payDebt(db,member));db.rollback();
        }
    }
    @Test void failedDailyTransferRollsBackWalletAuditAndDate()throws Exception {
        try(var db=database()) {
            taxes(db,100,0,0);db.createStatement().executeUpdate("UPDATE towns SET coins=9223372036854775807");
            db.setAutoCommit(false);assertThrows(SQLException.class,()->TaxStore.collect(db,"d1",0));db.rollback();
            assertEquals(1000,resident(db,"coins"));assertEquals(0,number(db,"SELECT COUNT(*) FROM resident_tax_payments"));
            assertEquals(0,number(db,"SELECT COUNT(*) FROM daily_economy_events"));
        }
    }
    @Test void ordinaryMembershipChangeClearsDebtAndAssistantRole()throws Exception {
        try(var db=database()) {
            db.createStatement().executeUpdate("UPDATE residents SET debt=500,days_til_evict=1 WHERE uuid='"+assistant+"'");
            db.createStatement().executeUpdate("UPDATE residents SET town_id=NULL WHERE uuid='"+assistant+"'");
            assertEquals(0,number(db,"SELECT debt FROM residents WHERE uuid='"+assistant+"'"));
            assertEquals(0,number(db,"SELECT COUNT(*) FROM town_assistants"));
        }
    }
    @Test void evictionCooldownPersistsUntilExpiryOrFormerCivilizationIsGone()throws Exception {
        try(var db=database()) {
            TaxStore.removeTownMembership(db,member,1,1000);
            assertThrows(IllegalArgumentException.class,()->TaxStore.validateRejoin(db,member,1001,43200000));
            assertDoesNotThrow(()->TaxStore.validateRejoin(db,member,43201000,43200000));
            db.createStatement().executeUpdate("DELETE FROM civilizations WHERE id=1");
            assertDoesNotThrow(()->TaxStore.validateRejoin(db,member,1001,43200000));
        }
    }
    @Test void civilizationTaxRoundsOnceAcrossTheTownCollection()throws Exception {
        try(var db=database()) {
            taxes(db,15,0,.05);db.createStatement().executeUpdate("DELETE FROM town_assistants");
            db.setAutoCommit(false);TaxStore.collect(db,"d1",0);db.commit();
            assertEquals(29,number(db,"SELECT coins FROM towns"));assertEquals(1,number(db,"SELECT coins FROM civilizations"));
            assertEquals(2970,number(db,"SELECT SUM(coins) FROM residents"));
        }
    }
}
