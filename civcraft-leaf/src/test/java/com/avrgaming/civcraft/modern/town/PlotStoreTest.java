package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlotStoreTest {
    final UUID mayor=UUID.randomUUID(),buyer=UUID.randomUUID(),other=UUID.randomUUID();
    final PlotStore.Key key=new PlotStore.Key("world",-1,2);
    Connection database()throws SQLException {
        var db=DriverManager.getConnection("jdbc:sqlite::memory:");
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE residents(uuid TEXT PRIMARY KEY,coins INTEGER,town_id INTEGER,civ_id INTEGER,updated_at INTEGER)");
            s.executeUpdate("CREATE TABLE civilizations(id INTEGER PRIMARY KEY,leader_uuid TEXT,coins INTEGER)");
            s.executeUpdate("CREATE TABLE towns(id INTEGER PRIMARY KEY,civ_id INTEGER,mayor_uuid TEXT,coins INTEGER)");
            s.executeUpdate("CREATE TABLE town_claims(world TEXT,chunk_x INTEGER,chunk_z INTEGER,town_id INTEGER,civ_id INTEGER,PRIMARY KEY(world,chunk_x,chunk_z))");
            s.executeUpdate("CREATE TABLE settlement_invites(target_uuid TEXT)");
            s.executeUpdate("CREATE TABLE daily_economy_events(event TEXT,day TEXT,processed_at INTEGER,PRIMARY KEY(event,day))");
            s.executeUpdate("INSERT INTO civilizations VALUES(1,'"+mayor+"',0)");
            s.executeUpdate("INSERT INTO towns VALUES(1,1,'"+mayor+"',0)");
            for(var id:new UUID[]{mayor,buyer,other})s.executeUpdate("INSERT INTO residents VALUES('"+id+"',2000,1,1,0)");
            s.executeUpdate("INSERT INTO town_claims VALUES('world',-1,2,1,1)");
        }
        TaxStore.install(db);PlotStore.install(db);db.setAutoCommit(false);return db;
    }
    long number(Connection db,String sql)throws SQLException {try(var s=db.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
    long wallet(Connection db,UUID id)throws SQLException{return number(db,"SELECT coins FROM residents WHERE uuid='"+id+"'");}
    @Test void townSaleAndPrivateResaleTransferMoneyOnlyOnce()throws Exception {
        try(var db=database()) {
            PlotStore.offer(db,key,mayor,500L);assertEquals(500,PlotStore.buy(db,key,buyer,0));db.commit();
            assertEquals(1500,wallet(db,buyer));assertEquals(500,number(db,"SELECT coins FROM towns"));
            assertEquals(buyer.toString(),PlotStore.find(db,key).owner());
            assertThrows(IllegalArgumentException.class,()->PlotStore.buy(db,key,other,1));db.rollback();
            PlotStore.offer(db,key,buyer,800L);PlotStore.buy(db,key,other,2);db.commit();
            assertEquals(2300,wallet(db,buyer));assertEquals(1200,wallet(db,other));
            assertEquals(500,number(db,"SELECT coins FROM towns"));assertEquals(800,PlotStore.find(db,key).value());
            assertEquals(2,number(db,"SELECT COUNT(*) FROM plot_sales"));
        }
    }
    @Test void purchasesRestrictAllActionsToOwnerUntilExplicitlyGranted()throws Exception {
        try(var db=database()) {
            assertTrue(PlotStore.allowed(db,key,other,"build"));
            PlotStore.offer(db,key,mayor,0L);PlotStore.buy(db,key,buyer,0);db.commit();
            for(var action:PlotStore.ACTIONS){assertTrue(PlotStore.allowed(db,key,buyer,action));assertFalse(PlotStore.allowed(db,key,other,action));}
            PlotStore.permission(db,key,buyer,"interact","others",true);db.commit();
            assertTrue(PlotStore.allowed(db,key,other,"interact"));assertFalse(PlotStore.allowed(db,key,other,"destroy"));
            PlotStore.group(db,key,buyer,"default",true);db.commit();assertTrue(PlotStore.allowed(db,key,other,"build"));
            PlotStore.permission(db,key,buyer,"build","group",false);db.commit();assertFalse(PlotStore.allowed(db,key,other,"build"));
            assertThrows(IllegalArgumentException.class,()->PlotStore.permission(db,key,other,"destroy","others",true));db.rollback();
        }
    }
    @Test void invalidPurchasesAndOverflowRollBackBuyerFundsAndOwnership()throws Exception {
        try(var db=database()) {
            PlotStore.offer(db,key,mayor,500L);db.commit();
            db.createStatement().executeUpdate("UPDATE towns SET coins=9223372036854775807");db.commit();
            assertThrows(SQLException.class,()->PlotStore.buy(db,key,buyer,0));db.rollback();
            assertEquals(2000,wallet(db,buyer));assertNull(PlotStore.find(db,key).owner());assertTrue(PlotStore.find(db,key).forSale());
            assertEquals(0,number(db,"SELECT COUNT(*) FROM plot_sales"));
            db.createStatement().executeUpdate("UPDATE residents SET town_id=NULL WHERE uuid='"+buyer+"'");db.commit();
            assertThrows(IllegalArgumentException.class,()->PlotStore.buy(db,key,buyer,0));db.rollback();assertEquals(2000,wallet(db,buyer));
        }
    }
    @Test void propertyTaxUsesPaidPriceEvenWhenAnotherSaleIsAdvertised()throws Exception {
        try(var db=database()) {
            PlotStore.offer(db,key,mayor,500L);PlotStore.buy(db,key,buyer,0);PlotStore.offer(db,key,buyer,1500L);
            db.createStatement().executeUpdate("UPDATE towns SET property_tax_rate=0.1");
            TaxStore.collect(db,"d1",0);db.commit();assertEquals(1450,wallet(db,buyer));
            assertEquals(50,number(db,"SELECT assessed FROM resident_tax_payments WHERE player_uuid='"+buyer+"'"));
        }
    }
    @Test void evictionRestoresTownGroupAndResetsPrivatePermissions()throws Exception {
        try(var db=database()) {
            PlotStore.offer(db,key,mayor,500L);PlotStore.buy(db,key,buyer,0);
            PlotStore.permission(db,key,buyer,"destroy","others",true);
            db.createStatement().executeUpdate("UPDATE residents SET debt=100,days_til_evict=1 WHERE uuid='"+buyer+"'");
            TaxStore.advanceGrace(db,"d1",10);db.commit();
            assertNull(PlotStore.find(db,key).owner());assertEquals("default",PlotStore.find(db,key).groups());
            assertTrue(PlotStore.allowed(db,key,other,"build"));
            db.createStatement().executeUpdate("UPDATE residents SET town_id=NULL WHERE uuid='"+other+"'");db.commit();
            assertFalse(PlotStore.allowed(db,key,other,"destroy"));
        }
    }
    @Test void ownerTransferRequiresSameTownAndPreservesTaxValue()throws Exception {
        try(var db=database()) {
            PlotStore.offer(db,key,mayor,500L);PlotStore.buy(db,key,buyer,0);db.commit();
            assertThrows(IllegalArgumentException.class,()->PlotStore.owner(db,key,mayor,other));db.rollback();
            PlotStore.owner(db,key,buyer,other);db.commit();assertEquals(500,PlotStore.find(db,key).value());
            assertEquals(other.toString(),PlotStore.find(db,key).owner());assertEquals(1500,wallet(db,buyer));
            db.createStatement().executeUpdate("UPDATE residents SET town_id=NULL WHERE uuid='"+buyer+"'");db.commit();
            assertThrows(IllegalArgumentException.class,()->PlotStore.owner(db,key,other,buyer));db.rollback();
        }
    }
    @Test void insufficientFundsAndBuyingOwnPlotLeaveSaleAndMoneyUnchanged()throws Exception {
        try(var db=database()) {
            PlotStore.offer(db,key,mayor,3000L);db.commit();
            assertThrows(IllegalArgumentException.class,()->PlotStore.buy(db,key,buyer,0));db.rollback();
            assertEquals(2000,wallet(db,buyer));assertTrue(PlotStore.find(db,key).forSale());
            assertEquals(0,number(db,"SELECT COUNT(*) FROM plot_sales"));
            PlotStore.offer(db,key,mayor,500L);PlotStore.buy(db,key,buyer,0);PlotStore.offer(db,key,buyer,800L);db.commit();
            assertThrows(IllegalArgumentException.class,()->PlotStore.buy(db,key,buyer,1));db.rollback();
            assertEquals(1500,wallet(db,buyer));assertEquals(500,number(db,"SELECT coins FROM towns"));
            assertTrue(PlotStore.find(db,key).forSale());assertEquals(1,number(db,"SELECT COUNT(*) FROM plot_sales"));
        }
    }
    @Test void privateSellerOverflowDoesNotChargeBuyerOrChangeOwner()throws Exception {
        try(var db=database()) {
            PlotStore.offer(db,key,mayor,500L);PlotStore.buy(db,key,buyer,0);PlotStore.offer(db,key,buyer,800L);
            db.createStatement().executeUpdate("UPDATE residents SET coins=9223372036854775807 WHERE uuid='"+buyer+"'");db.commit();
            assertThrows(SQLException.class,()->PlotStore.buy(db,key,other,1));db.rollback();
            assertEquals(2000,wallet(db,other));assertEquals(Long.MAX_VALUE,wallet(db,buyer));
            assertEquals(buyer.toString(),PlotStore.find(db,key).owner());assertTrue(PlotStore.find(db,key).forSale());
            assertEquals(1,number(db,"SELECT COUNT(*) FROM plot_sales"));
        }
    }
}
