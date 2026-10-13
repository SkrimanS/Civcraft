package com.avrgaming.civcraft.modern.government;

import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GovernmentStoreTest {
    private final UUID leader=UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final GovernmentDefinition despotism=new GovernmentDefinition("gov_despotism","Деспотизм","tech_productivity",1.1,1,0.8,0.8,1.25,0.5,1,0.1);
    private void schema(Connection db)throws SQLException {
        db.createStatement().execute("PRAGMA foreign_keys=ON");
        db.createStatement().executeUpdate("CREATE TABLE civilizations(id INTEGER PRIMARY KEY,leader_uuid TEXT,government TEXT)");
        db.createStatement().executeUpdate("CREATE TABLE civ_technologies(civ_id INTEGER,tech_id TEXT)");
        db.createStatement().executeUpdate("INSERT INTO civilizations VALUES(1,'"+leader+"','Tribalism')");
        GovernmentStore.install(db);
    }
    @Test void requiresLeaderAndTechnologyAndDoesNotWriteOnRejection()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);
            assertThrows(IllegalArgumentException.class,()->GovernmentStore.begin(db,1,UUID.randomUUID(),despotism,0));
            assertThrows(IllegalArgumentException.class,()->GovernmentStore.begin(db,1,leader,despotism,0));
            assertNull(GovernmentStore.transition(db,1));
            try(var result=db.createStatement().executeQuery("SELECT government FROM civilizations")){assertTrue(result.next());assertEquals("Tribalism",result.getString(1));}
        }
    }
    @Test void transitionWaitsAndFinishesExactlyOnce()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);db.createStatement().executeUpdate("INSERT INTO civ_technologies VALUES(1,'tech_productivity')");
            db.setAutoCommit(false);GovernmentStore.begin(db,1,leader,despotism,1000);db.commit();
            var transition=GovernmentStore.transition(db,1);
            assertThrows(IllegalArgumentException.class,()->GovernmentStore.begin(db,1,leader,despotism,1001));
            assertFalse(GovernmentStore.finish(db,transition,3600999,3600000));db.commit();
            assertTrue(GovernmentStore.finish(db,transition,3601000,3600000));db.commit();
            assertFalse(GovernmentStore.finish(db,transition,3601001,3600000));db.commit();
            assertNull(GovernmentStore.transition(db,1));
            try(var result=db.createStatement().executeQuery("SELECT government FROM civilizations")){assertTrue(result.next());assertEquals("gov_despotism",result.getString(1));}
        }
    }
    @Test void transactionRollbackAndReconnectPreservePendingTarget()throws Exception {
        var path=java.nio.file.Files.createTempFile("civcraft-government-",".sqlite");
        try {
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+path)) {
                schema(db);db.createStatement().executeUpdate("INSERT INTO civ_technologies VALUES(1,'tech_productivity')");
                db.setAutoCommit(false);GovernmentStore.begin(db,1,leader,despotism,50);db.rollback();
                assertNull(GovernmentStore.transition(db,1));
                GovernmentStore.begin(db,1,leader,despotism,100);db.commit();
            }
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+path)) {
                assertEquals("gov_despotism",GovernmentStore.transition(db,1).target());
                db.setAutoCommit(false);assertTrue(GovernmentStore.finish(db,GovernmentStore.transition(db,1),3600100,3600000));db.commit();
            }
        } finally {java.nio.file.Files.deleteIfExists(path);}
    }
    @Test void durationFollowsTownResidentsAndConfiguredCap() {
        assertEquals(0,GovernmentStore.durationMillis(0,24));
        assertEquals(3600000,GovernmentStore.durationMillis(1,24));
        assertEquals(86400000,GovernmentStore.durationMillis(40,24));
        assertEquals(7200000,GovernmentStore.durationMillis(40,2));
        assertEquals(1800000,GovernmentStore.durationMillis(0.5,24));
    }
}
