package com.avrgaming.civcraft.modern.culture;
import java.sql.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CultureClockStoreTest {
    Connection database()throws SQLException {
        var db=DriverManager.getConnection("jdbc:sqlite::memory:");db.createStatement().executeUpdate("CREATE TABLE towns(id INTEGER PRIMARY KEY,accumulated_culture INTEGER NOT NULL)");
        db.createStatement().executeUpdate("INSERT INTO towns VALUES(1,0)");CultureClockStore.install(db);CultureClockStore.start(db,3600000,10);db.setAutoCommit(false);return db;
    }
    long number(Connection db,String sql)throws SQLException{try(var s=db.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
    @Test void roundsSourcesSeparatelyAndKeepsSmallUnusedScience()throws Exception {
        try(var db=database()) {
            CultureClockStore.science(db,1,4);db.commit();
            var plans=List.of(new CultureClockStore.Production(1,.4,4));
            assertEquals(0L,CultureClockStore.hourly(db,3600000,11,plans,.1).get(1L));db.commit();
            assertEquals(4,number(db,"SELECT unused_beakers FROM culture_science"));
            CultureClockStore.science(db,1,2);db.commit();
            assertEquals(1L,CultureClockStore.hourly(db,3600000,12,List.of(new CultureClockStore.Production(1,.4,6)),.1).get(1L));db.commit();
            assertEquals(0,number(db,"SELECT unused_beakers FROM culture_science"));assertEquals(1,number(db,"SELECT accumulated_culture FROM towns"));
        }
    }
    @Test void hourJournalPreventsDuplicateOrRestartPayoutAndSkipsOfflineHours()throws Exception {
        try(var db=database()) {
            var plans=List.of(new CultureClockStore.Production(1,25,0));
            assertTrue(CultureClockStore.hourly(db,3600000,10,plans,.1).isEmpty());db.commit();
            assertEquals(25L,CultureClockStore.hourly(db,3600000,11,plans,.1).get(1L));db.commit();
            assertTrue(CultureClockStore.hourly(db,3600000,11,plans,.1).isEmpty());db.commit();
            CultureClockStore.start(db,3600000,30);db.commit();
            assertTrue(CultureClockStore.hourly(db,3600000,30,plans,.1).isEmpty());db.commit();
            CultureClockStore.hourly(db,3600000,31,plans,.1);db.commit();assertEquals(50,number(db,"SELECT accumulated_culture FROM towns"));
        }
    }
    @Test void rollbackRestoresHourMarkerScienceAndEveryTown()throws Exception {
        try(var db=database()) {
            CultureClockStore.science(db,1,20);db.commit();
            var plans=List.of(new CultureClockStore.Production(1,25,20),new CultureClockStore.Production(99,10,0));
            assertThrows(SQLException.class,()->CultureClockStore.hourly(db,3600000,11,plans,.1));db.rollback();
            assertTrue(CultureClockStore.due(db,3600000,11));assertEquals(0,number(db,"SELECT accumulated_culture FROM towns"));
            assertEquals(20,number(db,"SELECT unused_beakers FROM culture_science"));assertEquals(0,number(db,"SELECT COUNT(*) FROM culture_hours"));
        }
    }
    @Test void rejectsOverflowAndInvalidRatesWithoutPartialCulture()throws Exception {
        try(var db=database()) {
            db.createStatement().executeUpdate("UPDATE towns SET accumulated_culture=9223372036854775807");db.commit();
            assertThrows(SQLException.class,()->CultureClockStore.hourly(db,3600000,11,List.of(new CultureClockStore.Production(1,1,0)),.1));db.rollback();
            assertEquals(Long.MAX_VALUE,number(db,"SELECT accumulated_culture FROM towns"));
            assertThrows(SQLException.class,()->CultureClockStore.science(db,1,Double.NaN));
        }
    }
}
