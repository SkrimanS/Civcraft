package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankDailyStoreTest {
    void accounts(Connection db)throws SQLException {
        new UpgradeStoreTest().schema(db);
        db.createStatement().executeUpdate("UPDATE towns SET coins=10000");
        db.createStatement().executeUpdate("INSERT INTO bank_interest_accounts VALUES(1,0.01,10000)");
        db.createStatement().executeUpdate("INSERT INTO structure_builds VALUES(1,1,'s_bank','COMPLETE')");
    }
    @Test void paysOncePerDayAndWithdrawalRedeploymentCannotInflatePrincipal()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            accounts(db);
            db.createStatement().executeUpdate("UPDATE towns SET coins=2000");
            db.createStatement().executeUpdate("UPDATE towns SET coins=10000");
            db.setAutoCommit(false);
            assertEquals(20L,BankDailyStore.interest(db,"2026-10-13",0).get(1L));db.commit();
            assertTrue(BankDailyStore.interest(db,"2026-10-13",1).isEmpty());db.commit();
            assertEquals(10020,new UpgradeStoreTest().coins(db));
            assertEquals(100L,BankDailyStore.interest(db,"2026-10-14",2).get(1L));db.commit();
            assertEquals(10120,new UpgradeStoreTest().coins(db));
        }
    }
    @Test void destroyedBankDoesNotEarnAndOverflowRollsBackDayMarker()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            accounts(db);db.createStatement().executeUpdate("UPDATE structure_builds SET status='DEMOLISHED'");
            db.setAutoCommit(false);assertTrue(BankDailyStore.interest(db,"2026-10-13",0).isEmpty());db.commit();
            assertEquals(10000,new UpgradeStoreTest().coins(db));
            db.createStatement().executeUpdate("UPDATE structure_builds SET status='COMPLETE'");
            db.createStatement().executeUpdate("UPDATE towns SET coins="+Long.MAX_VALUE);
            db.createStatement().executeUpdate("UPDATE bank_interest_accounts SET principal="+Long.MAX_VALUE+",interest_rate=1");db.commit();
            assertThrows(SQLException.class,()->BankDailyStore.interest(db,"2026-10-14",0));db.rollback();
            db.createStatement().executeUpdate("UPDATE towns SET coins=100");db.commit();
            assertEquals(100L,BankDailyStore.interest(db,"2026-10-14",1).get(1L));db.commit();
        }
    }
    @Test void dayMarkerSurvivesDatabaseReconnect()throws Exception {
        var file=java.nio.file.Files.createTempFile("civcraft-bank-day-",".sqlite");
        try {
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+file)) {
                accounts(db);db.setAutoCommit(false);assertEquals(100L,BankDailyStore.interest(db,"2026-10-13",0).get(1L));db.commit();
            }
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+file)) {
                db.setAutoCommit(false);assertTrue(BankDailyStore.interest(db,"2026-10-13",1).isEmpty());db.commit();
                assertEquals(10100,new UpgradeStoreTest().coins(db));
            }
        } finally {java.nio.file.Files.deleteIfExists(file);}
    }
}
