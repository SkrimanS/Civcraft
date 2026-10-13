package com.avrgaming.civcraft.modern.trade;

import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradeStoreTest {
    private void schema(Connection db)throws SQLException {
        db.createStatement().execute("PRAGMA foreign_keys=ON");
        db.createStatement().executeUpdate("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY)");
        db.createStatement().executeUpdate("CREATE TABLE towns(id INTEGER PRIMARY KEY,coins INTEGER)");
        db.createStatement().executeUpdate("CREATE TABLE residents(uuid TEXT PRIMARY KEY,coins INTEGER)");
        db.createStatement().executeUpdate("INSERT INTO towns VALUES(1,10)");
        db.createStatement().executeUpdate("INSERT INTO residents VALUES('player',100)");
        TradeStore.install(db);
    }
    @Test void reservesWalletAndFinishesWithOneCreditAndFee()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);db.setAutoCommit(false);
            var pending=TradeStore.prepare(db,"trade","player",new byte[]{42},20,50,1L,5);db.commit();
            assertEquals(80,balance(db));
            assertThrows(IllegalArgumentException.class,()->TradeStore.prepare(db,"second","player",new byte[0],0,0,null,0));
            assertTrue(TradeStore.finish(db,pending));db.commit();
            assertFalse(TradeStore.finish(db,pending));db.commit();
            assertEquals(130,balance(db));assertNull(TradeStore.pending(db,"player"));
            assertArrayEquals(new byte[]{42},TradeStore.checkpoint(db,"player").inventory());
            assertEquals("trade",TradeStore.checkpoint(db,"player").id());
            try(var result=db.createStatement().executeQuery("SELECT coins FROM towns")){assertTrue(result.next());assertEquals(15,result.getLong(1));}
            try(var result=db.createStatement().executeQuery("SELECT length(inventory) FROM trade_receipts")){assertTrue(result.next());assertEquals(0,result.getInt(1));}
        }
    }
    @Test void rejectionAndFailedTransactionLeaveWalletAndPricesUntouched()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);db.setAutoCommit(false);
            assertThrows(IllegalArgumentException.class,()->TradeStore.prepare(db,"bad","player",new byte[0],101,0,null,0));db.rollback();
            assertEquals(100,balance(db));assertNull(TradeStore.pending(db,"player"));
            TradeStore.price(db,"ore",MarketPrice.initial(10,0.15));db.commit();
            TradeStore.prepare(db,"trade","player",new byte[]{42},50,0,null,0);
            TradeStore.updatePrice(db,"ore",MarketPrice.initial(20,0.15));db.rollback();
            assertEquals(100,balance(db));assertNull(TradeStore.pending(db,"player"));
            assertEquals(10,TradeStore.price(db,"ore",MarketPrice.initial(20,0.15)).sell());
        }
    }
    @Test void pendingInventoryAndEscrowSurviveReconnect()throws Exception {
        var file=java.nio.file.Files.createTempFile("civcraft-trade-",".sqlite");
        try {
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+file)) {
                schema(db);db.setAutoCommit(false);
                TradeStore.prepare(db,"trade","player",new byte[]{1,2,3},50,25,1L,2);db.commit();
            }
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+file)) {
                var pending=TradeStore.pending(db,"player");assertArrayEquals(new byte[]{1,2,3},pending.inventory());
                assertEquals(50,balance(db));db.setAutoCommit(false);
                assertTrue(TradeStore.finish(db,pending));db.rollback();
                assertEquals(50,balance(db));assertNotNull(TradeStore.pending(db,"player"));
                assertTrue(TradeStore.finish(db,pending));db.commit();
                assertEquals(75,balance(db));
            }
        } finally {java.nio.file.Files.deleteIfExists(file);}
    }
    private long balance(Connection db)throws SQLException {
        try(var result=db.createStatement().executeQuery("SELECT coins FROM residents WHERE uuid='player'")){assertTrue(result.next());return result.getLong(1);}
    }
    @Test void removedFeeTownRefundsVisitorAndDoesNotBlockRecovery()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);db.setAutoCommit(false);
            var pending=TradeStore.prepare(db,"trade","player",new byte[]{42},0,486,1L,26);db.commit();
            db.createStatement().executeUpdate("DELETE FROM towns WHERE id=1");db.commit();
            assertTrue(TradeStore.finish(db,pending));db.commit();
            assertEquals(612,balance(db));
            assertFalse(TradeStore.finish(db,pending));db.commit();
            assertEquals(612,balance(db));
            try(var result=db.createStatement().executeQuery("SELECT refunded_fee FROM trade_receipts WHERE id='trade'")){assertTrue(result.next());assertEquals(26,result.getLong(1));}
        }
    }
}
