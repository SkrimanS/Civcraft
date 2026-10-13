package com.avrgaming.civcraft.modern.build;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ConstructionClockStoreTest {
    Connection database()throws SQLException {
        var db=DriverManager.getConnection("jdbc:sqlite::memory:");db.createStatement().executeUpdate("CREATE TABLE civcraft_meta(key TEXT PRIMARY KEY,value TEXT)");
        db.createStatement().executeUpdate("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY,status TEXT,started_at INTEGER,finish_at INTEGER)");
        db.createStatement().executeUpdate("INSERT INTO structure_builds VALUES(1,'QUEUED',1000,11000),(2,'BUILDING',1000,11000),(3,'COMPLETE',1000,11000)");
        db.setAutoCommit(false);return db;
    }
    long value(Connection db,int id,String column)throws SQLException{try(var s=db.createStatement();var r=s.executeQuery("SELECT "+column+" FROM structure_builds WHERE id="+id)){r.next();return r.getLong(1);}}
    @Test void suspendsOfflineElapsedTimeAndPreservesRemainingWork()throws Exception {
        try(var db=database()) {
            ConstructionClockStore.checkpoint(db,4000);db.commit();
            assertEquals(2,ConstructionClockStore.resume(db,104000));db.commit();
            assertEquals(101000,value(db,1,"started_at"));assertEquals(111000,value(db,1,"finish_at"));
            assertEquals(7000,value(db,1,"finish_at")-104000);assertEquals(.3,(104000-value(db,1,"started_at"))/10000.0);
            assertEquals(11000,value(db,3,"finish_at"));
            ConstructionClockStore.resume(db,104000);db.commit();assertEquals(111000,value(db,1,"finish_at"));
        }
    }
    @Test void firstMigrationDoesNotInventDowntimeAndClockRollbackKeepsProgress()throws Exception {
        try(var db=database()) {
            assertEquals(0,ConstructionClockStore.resume(db,4000));db.commit();assertEquals(11000,value(db,1,"finish_at"));
            ConstructionClockStore.resume(db,3000);db.commit();assertEquals(0,value(db,1,"started_at"));assertEquals(10000,value(db,1,"finish_at"));
        }
    }
    @Test void overflowRollsBackAllClocksAndCheckpoint()throws Exception {
        try(var db=database()) {
            ConstructionClockStore.checkpoint(db,4000);db.createStatement().executeUpdate("UPDATE structure_builds SET finish_at=9223372036854775807 WHERE id=2");db.commit();
            assertThrows(SQLException.class,()->ConstructionClockStore.resume(db,5000));db.rollback();
            assertEquals(11000,value(db,1,"finish_at"));
            db.createStatement().executeUpdate("UPDATE structure_builds SET finish_at=11000 WHERE id=2");db.commit();
            ConstructionClockStore.resume(db,5000);db.commit();assertEquals(12000,value(db,1,"finish_at"));
        }
    }
}
