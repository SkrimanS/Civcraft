package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ConquestStoreTest {
    Connection database()throws Exception {
        var db=DriverManager.getConnection("jdbc:sqlite::memory:");try(var s=db.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");s.execute("CREATE TABLE civilizations(id INTEGER PRIMARY KEY)");s.execute("INSERT INTO civilizations VALUES(1),(2),(3)");
            s.execute("CREATE TABLE towns(id INTEGER PRIMARY KEY,civ_id INTEGER REFERENCES civilizations(id))");s.execute("INSERT INTO towns VALUES(1,1),(2,2),(3,2)");
            s.execute("CREATE TABLE residents(uuid TEXT PRIMARY KEY,town_id INTEGER,civ_id INTEGER,updated_at INTEGER)");s.execute("INSERT INTO residents VALUES('member',2,2,0),('homeless',NULL,2,0)");
            s.execute("CREATE TABLE town_claims(town_id INTEGER,civ_id INTEGER)");s.execute("INSERT INTO town_claims VALUES(2,2),(3,2)");
            s.execute("CREATE TABLE war_defeated_towns(war_start INTEGER,town_id INTEGER,conqueror INTEGER)");s.execute("CREATE TABLE war_defeated_civs(war_start INTEGER,civ_id INTEGER,conqueror INTEGER)");
            s.execute("CREATE TABLE war_inactive_towns(war_start INTEGER,town_id INTEGER)");
            s.execute("CREATE TABLE diplomacy_relations(first_civ INTEGER,second_civ INTEGER,status TEXT,aggressor INTEGER,changed_at INTEGER)");s.execute("INSERT INTO diplomacy_relations VALUES(1,2,'WAR',1,0),(2,3,'ALLY',NULL,0)");
            s.execute("CREATE TABLE diplomacy_requests(from_civ INTEGER,to_civ INTEGER,state TEXT)");s.execute("INSERT INTO diplomacy_requests VALUES(2,3,'PENDING')");
            s.execute("CREATE TABLE civ_advisors(civ_id INTEGER)");s.execute("INSERT INTO civ_advisors VALUES(2)");s.execute("CREATE TABLE settlement_invites(kind TEXT,settlement_id INTEGER)");s.execute("INSERT INTO settlement_invites VALUES('CIV',2)");
        }WarClockStore.install(db);ConquestStore.install(db);db.createStatement().execute("INSERT INTO war_sessions VALUES(100,200,'CLOSED')");db.setAutoCommit(false);db.commit();return db;
    }
    long value(Connection db,String query)throws Exception {try(var r=db.createStatement().executeQuery(query)){assertTrue(r.next());return r.getLong(1);}}
    @Test void cityCaptureMovesClaimsAndResidentsAndReplayDoesNotTransferAgain()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("INSERT INTO war_defeated_towns VALUES(100,2,1)");db.commit();
            assertEquals(1,ConquestStore.processClosed(db,false,201).getFirst().towns());db.commit();
            assertEquals(1,value(db,"SELECT civ_id FROM towns WHERE id=2"));assertEquals(1,value(db,"SELECT civ_id FROM town_claims WHERE town_id=2"));assertEquals(1,value(db,"SELECT civ_id FROM residents WHERE uuid='member'"));
            assertEquals(2,value(db,"SELECT mother_civ FROM town_conquests WHERE town_id=2"));assertFalse(ConquestStore.conquered(db,2));
            assertTrue(ConquestStore.processClosed(db,false,300).isEmpty());db.commit();assertEquals(1,value(db,"SELECT COUNT(*) FROM war_town_transfers"));
        }
    }
    @Test void recaptureKeepsTheOriginalMotherlandAndLiberationClearsIt()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("INSERT INTO war_defeated_towns VALUES(100,2,1)");ConquestStore.processClosed(db,false,201);db.commit();
            db.createStatement().execute("INSERT INTO war_sessions VALUES(300,400,'CLOSED')");db.createStatement().execute("INSERT INTO war_defeated_towns VALUES(300,2,3)");ConquestStore.processClosed(db,false,401);db.commit();assertEquals(2,value(db,"SELECT mother_civ FROM town_conquests WHERE town_id=2"));
            db.createStatement().execute("INSERT INTO war_sessions VALUES(500,600,'CLOSED')");db.createStatement().execute("INSERT INTO war_defeated_towns VALUES(500,2,2)");ConquestStore.processClosed(db,false,601);db.commit();
            assertEquals(2,value(db,"SELECT civ_id FROM towns WHERE id=2"));try(var r=db.createStatement().executeQuery("SELECT mother_civ FROM town_conquests WHERE town_id=2")){r.next();assertNull(r.getObject(1));}
        }
    }
    @Test void capitolDefeatTransfersAllRemainingCitiesAndMakesTheCivilizationDormant()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("INSERT INTO war_defeated_towns VALUES(100,2,1)");db.createStatement().execute("INSERT INTO war_defeated_civs VALUES(100,2,1)");
            var result=ConquestStore.processClosed(db,false,201).getFirst();db.commit();assertEquals(2,result.towns());assertEquals(1,result.civilizations());assertTrue(ConquestStore.conquered(db,2));
            assertEquals(0,value(db,"SELECT COUNT(*) FROM towns WHERE civ_id=2"));assertEquals(0,value(db,"SELECT COUNT(*) FROM diplomacy_relations WHERE first_civ=2 OR second_civ=2"));
            assertEquals(0,value(db,"SELECT COUNT(*) FROM diplomacy_requests WHERE state='PENDING'"));assertEquals(0,value(db,"SELECT COUNT(*) FROM civ_advisors"));assertEquals(0,value(db,"SELECT COUNT(*) FROM settlement_invites"));
            try(var r=db.createStatement().executeQuery("SELECT civ_id FROM residents WHERE uuid='homeless'")){r.next();assertNull(r.getObject(1));}
        }
    }
    @Test void casualModeResetsOnlyTheWarAndDoesNotChangeOwnership()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("INSERT INTO war_defeated_towns VALUES(100,2,1)");db.createStatement().execute("INSERT INTO war_defeated_civs VALUES(100,2,1)");
            assertTrue(ConquestStore.processClosed(db,true,201).getFirst().casual());db.commit();assertEquals(2,value(db,"SELECT civ_id FROM towns WHERE id=2"));assertFalse(ConquestStore.conquered(db,2));
            try(var r=db.createStatement().executeQuery("SELECT status FROM diplomacy_relations WHERE first_civ=1 AND second_civ=2")){r.next();assertEquals("NEUTRAL",r.getString(1));}
            assertEquals(0,value(db,"SELECT COUNT(*) FROM war_town_transfers"));
        }
    }
    @Test void failedResultRollsBackAllEarlierTransfersAndCanBeRetried()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("INSERT INTO war_defeated_towns VALUES(100,2,1),(100,999,1)");db.commit();
            assertThrows(SQLException.class,()->ConquestStore.processClosed(db,false,201));db.rollback();
            assertEquals(2,value(db,"SELECT civ_id FROM towns WHERE id=2"));assertEquals(0,value(db,"SELECT COUNT(*) FROM war_results"));assertEquals(0,value(db,"SELECT COUNT(*) FROM war_town_transfers"));
            db.createStatement().execute("DELETE FROM war_defeated_towns WHERE town_id=999");db.commit();assertEquals(1,ConquestStore.processClosed(db,false,202).getFirst().towns());db.commit();
        }
    }
    @Test void activePeriodCannotApplyCaptureEarly()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("UPDATE war_sessions SET state='ACTIVE'");db.createStatement().execute("INSERT INTO war_defeated_towns VALUES(100,2,1)");db.commit();assertTrue(ConquestStore.processClosed(db,false,150).isEmpty());db.commit();assertEquals(2,value(db,"SELECT civ_id FROM towns WHERE id=2"));
        }
    }
    @Test void transferHistoryDoesNotPreventDeletingAnOldTown()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("INSERT INTO war_defeated_towns VALUES(100,2,1)");db.createStatement().execute("INSERT INTO war_inactive_towns VALUES(100,2)");ConquestStore.processClosed(db,false,201);db.commit();
            assertEquals(0,value(db,"SELECT COUNT(*) FROM war_defeated_towns"));assertEquals(0,value(db,"SELECT COUNT(*) FROM war_inactive_towns"));
            db.createStatement().execute("DELETE FROM towns WHERE id=2");db.commit();assertEquals(1,value(db,"SELECT COUNT(*) FROM war_town_transfers WHERE town_id=2"));
        }
    }
}
