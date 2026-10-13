package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UpkeepStoreTest {
    void schema(Connection db)throws SQLException {
        db.createStatement().executeUpdate("CREATE TABLE towns(id INTEGER PRIMARY KEY,coins INTEGER)");
        db.createStatement().executeUpdate("CREATE TABLE civilizations(id INTEGER PRIMARY KEY,coins INTEGER)");
        db.createStatement().executeUpdate("INSERT INTO towns VALUES(1,100),(2,100)");
        db.createStatement().executeUpdate("INSERT INTO civilizations VALUES(1,200)");
        BankDailyStore.install(db);UpkeepStore.install(db);
    }
    long value(Connection db,String table,long id,String column)throws SQLException {
        try(var query=db.prepareStatement("SELECT "+column+" FROM "+table+" WHERE id=?")){query.setLong(1,id);try(var row=query.executeQuery()){assertTrue(row.next());return row.getLong(1);}}
    }
    @Test void routesCapitalShortfallAndKeepsNonCapitalDebtLocal()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);db.setAutoCommit(false);
            var charges=List.of(new UpkeepStore.Charge(1,1,true,500),new UpkeepStore.Charge(2,1,false,300));
            UpkeepStore.daily(db,"2026-10-13",0,charges,Map.of(1L,500L));db.commit();
            assertEquals(0,value(db,"towns",1,"coins"));assertEquals(0,value(db,"towns",1,"debt"));
            assertEquals(200,value(db,"towns",2,"debt"));assertEquals(700,value(db,"civilizations",1,"debt"));
            assertEquals(1,value(db,"civilizations",1,"days_in_debt"));assertEquals(1,value(db,"towns",2,"days_in_debt"));
            assertTrue(UpkeepStore.daily(db,"2026-10-13",1,charges,Map.of(1L,500L)).isEmpty());db.commit();
            assertEquals(700,value(db,"civilizations",1,"debt"));
        }
    }
    @Test void transactionFailureDoesNotLeavePartialWithdrawalsOrDayMarker()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);db.setAutoCommit(false);
            var bad=List.of(new UpkeepStore.Charge(1,1,false,50),new UpkeepStore.Charge(99,1,false,50));
            assertThrows(SQLException.class,()->UpkeepStore.daily(db,"2026-10-13",0,bad,Map.of()));db.rollback();
            assertEquals(100,value(db,"towns",1,"coins"));
            var correct=List.of(new UpkeepStore.Charge(1,1,false,50));
            assertEquals(1,UpkeepStore.daily(db,"2026-10-13",1,correct,Map.of()).size());db.commit();
            assertEquals(50,value(db,"towns",1,"coins"));
        }
    }
    @Test void debtRepaymentUsesAvailableTreasuryAndClearsDebtDays()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);db.setAutoCommit(false);
            UpkeepStore.daily(db,"2026-10-13",0,List.of(new UpkeepStore.Charge(2,1,false,300)),Map.of());db.commit();
            db.createStatement().executeUpdate("UPDATE towns SET coins=500 WHERE id=2");db.commit();
            assertEquals(50,UpkeepStore.payDebt(db,"towns",2,50));db.commit();
            assertEquals(150,value(db,"towns",2,"debt"));
            assertEquals(150,UpkeepStore.payDebt(db,"towns",2,Long.MAX_VALUE));db.commit();
            assertEquals(300,value(db,"towns",2,"coins"));assertEquals(0,value(db,"towns",2,"days_in_debt"));
            assertThrows(IllegalArgumentException.class,()->UpkeepStore.payDebt(db,"towns",2,100));
        }
    }
    @Test void completedDaySurvivesDatabaseReconnect()throws Exception {
        var file=java.nio.file.Files.createTempFile("civcraft-upkeep-",".sqlite");
        try {
            var charges=List.of(new UpkeepStore.Charge(1,1,true,500));
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+file)) {
                schema(db);db.setAutoCommit(false);UpkeepStore.daily(db,"2026-10-13",0,charges,Map.of());db.commit();
            }
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+file)) {
                db.setAutoCommit(false);assertTrue(UpkeepStore.daily(db,"2026-10-13",1,charges,Map.of()).isEmpty());db.commit();
                assertEquals(200,value(db,"civilizations",1,"debt"));
            }
        } finally {java.nio.file.Files.deleteIfExists(file);}
    }
    @Test void depositsPayDebtFirstAndPutOnlyRemainderInTreasury()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);
            db.createStatement().executeUpdate("UPDATE towns SET debt=200,days_in_debt=3 WHERE id=2");
            UpkeepStore.deposit(db,"towns",2,50);
            assertEquals(150,value(db,"towns",2,"debt"));assertEquals(100,value(db,"towns",2,"coins"));
            assertEquals(3,value(db,"towns",2,"days_in_debt"));
            UpkeepStore.deposit(db,"towns",2,180);
            assertEquals(0,value(db,"towns",2,"debt"));assertEquals(130,value(db,"towns",2,"coins"));
            assertEquals(0,value(db,"towns",2,"days_in_debt"));
            db.createStatement().executeUpdate("UPDATE civilizations SET coins=9223372036854775807 WHERE id=1");
            assertThrows(SQLException.class,()->UpkeepStore.deposit(db,"civilizations",1,1));
            assertEquals(Long.MAX_VALUE,value(db,"civilizations",1,"coins"));
        }
    }
    @Test void failedTransferRollsBackPlayerWalletAndRejectsWrongMembership()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);var player=UUID.randomUUID();
            db.createStatement().executeUpdate("CREATE TABLE residents(id INTEGER,uuid TEXT,coins INTEGER,town_id INTEGER,civ_id INTEGER)");
            try(var insert=db.prepareStatement("INSERT INTO residents VALUES(1,?,500,99,1)")){insert.setString(1,player.toString());insert.executeUpdate();}
            db.setAutoCommit(false);
            assertThrows(SQLException.class,()->UpkeepStore.transferDeposit(db,"towns",99,player,100));db.rollback();
            assertEquals(500,value(db,"residents",1,"coins"));
            assertThrows(IllegalArgumentException.class,()->UpkeepStore.transferDeposit(db,"towns",2,player,100));db.rollback();
            assertEquals(500,value(db,"residents",1,"coins"));
            UpkeepStore.transferDeposit(db,"civilizations",1,player,100);db.commit();
            assertEquals(400,value(db,"residents",1,"coins"));assertEquals(300,value(db,"civilizations",1,"coins"));
        }
    }
}
