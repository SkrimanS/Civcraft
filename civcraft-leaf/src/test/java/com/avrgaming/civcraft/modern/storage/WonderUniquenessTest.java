package com.avrgaming.civcraft.modern.storage;

import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WonderUniquenessTest {
    private void schema(Connection db) throws SQLException {
        db.createStatement().executeUpdate("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY, structure_id TEXT, town_id INTEGER, status TEXT, cost INTEGER DEFAULT 500)");
        db.createStatement().executeUpdate("CREATE TABLE towns(id INTEGER PRIMARY KEY, coins INTEGER)");
        db.createStatement().executeUpdate("INSERT INTO towns VALUES(1,100),(2,200)");
        WonderUniqueness.install(db);
    }

    @Test void parallelConstructionHasOneWinnerAndRefundsLoserOnlyOnce() throws Exception {
        try (Connection db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);
            insert(db,"w_pyramid",1,"QUEUED");
            insert(db,"W_PYRAMID",2,"QUEUED");
            db.createStatement().executeUpdate("UPDATE structure_builds SET status='COMPLETE' WHERE town_id=1");
            assertThrows(SQLException.class,()->db.createStatement().executeUpdate("UPDATE structure_builds SET status='COMPLETE' WHERE town_id=2"));
            db.setAutoCommit(false);
            assertTrue(WonderUniqueness.rollbackLosingBuild(db,2));
            db.commit();
            assertFalse(WonderUniqueness.rollbackLosingBuild(db,2));
            db.commit();
            try(var result=db.createStatement().executeQuery("SELECT coins FROM towns WHERE id=2")) {
                assertTrue(result.next());assertEquals(700,result.getInt(1));
            }
            for(String id:new String[]{"w_colosseum","w_battledome","s_bank"}) {
                insert(db,id,1,"COMPLETE");insert(db,id,2,"COMPLETE");
            }
        }
    }

    @Test void demolitionKeepsWinnerUntilTerrainIsRestored() throws Exception {
        try(Connection db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);insert(db,"w_pyramid",1,"DEMOLISH_PENDING");
            assertThrows(SQLException.class,()->insert(db,"w_pyramid",2,"COMPLETE"));
            db.createStatement().executeUpdate("UPDATE structure_builds SET status='DEMOLISHED'");
            insert(db,"w_pyramid",2,"COMPLETE");
        }
    }

    @Test void winnerSurvivesReconnectAndFailedRefundTransactionCanRetry() throws Exception {
        var file=java.nio.file.Files.createTempFile("civcraft-wonders-",".sqlite");
        try {
            try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+file)) {
                schema(db);insert(db,"w_pyramid",1,"COMPLETE");insert(db,"w_pyramid",2,"QUEUED");
                db.setAutoCommit(false);
                assertTrue(WonderUniqueness.rollbackLosingBuild(db,2));db.rollback();
            }
            try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+file)) {
                WonderUniqueness.install(db);
                assertThrows(SQLException.class,()->insert(db,"w_pyramid",2,"COMPLETE"));
                db.setAutoCommit(false);
                assertTrue(WonderUniqueness.rollbackLosingBuild(db,2));db.commit();
                try(var result=db.createStatement().executeQuery("SELECT coins FROM towns WHERE id=2")) {
                    assertTrue(result.next());assertEquals(700,result.getInt(1));
                }
            }
        } finally {java.nio.file.Files.deleteIfExists(file);}
    }

    private void insert(Connection db,String id,long town,String status)throws SQLException {
        try(var query=db.prepareStatement("INSERT INTO structure_builds(structure_id,town_id,status) VALUES(?,?,?)")) {
            query.setString(1,id);query.setLong(2,town);query.setString(3,status);query.executeUpdate();
        }
    }
}
