package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OccupationActionsStoreTest {
    final UUID leader=new UUID(0,1),motherLeader=new UUID(0,2),thirdLeader=new UUID(0,3),member=new UUID(0,4);
    Connection database()throws Exception {
        var db=DriverManager.getConnection("jdbc:sqlite::memory:");try(var s=db.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");s.execute("CREATE TABLE civilizations(id INTEGER PRIMARY KEY,name TEXT,leader_uuid TEXT,capital_town_id INTEGER)");
            s.execute("INSERT INTO civilizations VALUES(1,'Victor','"+leader+"',1),(2,'Mother','"+motherLeader+"',2),(3,'Third','"+thirdLeader+"',4)");
            s.execute("CREATE TABLE towns(id INTEGER PRIMARY KEY,name TEXT,civ_id INTEGER)");s.execute("INSERT INTO towns VALUES(1,'Home',1),(2,'Capital',1),(3,'Colony',1),(4,'Foreign',3)");
            s.execute("CREATE TABLE residents(uuid TEXT PRIMARY KEY,town_id INTEGER,civ_id INTEGER,updated_at INTEGER)");s.execute("INSERT INTO residents VALUES('"+leader+"',1,1,0),('"+motherLeader+"',2,1,0),('"+thirdLeader+"',4,3,0),('"+member+"',3,1,0)");
            s.execute("CREATE TABLE town_claims(town_id INTEGER,civ_id INTEGER)");s.execute("INSERT INTO town_claims VALUES(2,1),(3,1),(4,3)");
            s.execute("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY,town_id INTEGER,structure_id TEXT,status TEXT)");s.execute("INSERT INTO structure_builds VALUES(1,2,'s_capitol','COMPLETE')");
        }WarClockStore.install(db);ConquestStore.install(db);OccupationActionsStore.install(db);
        db.createStatement().execute("INSERT INTO town_conquests VALUES(2,2,0),(3,2,0),(4,2,0)");db.createStatement().execute("INSERT INTO civ_conquests VALUES(2,0)");db.setAutoCommit(false);db.commit();return db;
    }
    long value(Connection db,String sql)throws Exception {try(var r=db.createStatement().executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
    @Test void capitalLiberationRevivesMotherlandAndReturnsOnlyTheVictorsCapturedCities()throws Exception {
        try(var db=database()) {
            var result=OccupationActionsStore.liberate(db,"Capital",leader,100);db.commit();assertTrue(result.civilization());assertEquals(2,result.towns());assertFalse(ConquestStore.conquered(db,2));
            assertEquals(2,value(db,"SELECT civ_id FROM towns WHERE id=2"));assertEquals(2,value(db,"SELECT civ_id FROM towns WHERE id=3"));assertEquals(3,value(db,"SELECT civ_id FROM towns WHERE id=4"));
            assertEquals(2,value(db,"SELECT civ_id FROM residents WHERE uuid='"+motherLeader+"'"));assertEquals(2,value(db,"SELECT civ_id FROM town_claims WHERE town_id=3"));
            assertEquals(1,value(db,"SELECT COUNT(*) FROM town_conquests WHERE mother_civ IS NOT NULL"));assertEquals(1,value(db,"SELECT COUNT(*) FROM occupation_actions"));
            assertThrows(IllegalArgumentException.class,()->OccupationActionsStore.liberate(db,"Capital",leader,101));db.rollback();assertEquals(1,value(db,"SELECT COUNT(*) FROM occupation_actions"));
        }
    }
    @Test void foreignLeaderAndOrdinaryMemberCannotReleaseCities()throws Exception {
        try(var db=database()) {
            for(UUID actor:new UUID[]{motherLeader,thirdLeader,member}){assertThrows(IllegalArgumentException.class,()->OccupationActionsStore.liberate(db,"Capital",actor,100));db.rollback();}
            assertEquals(1,value(db,"SELECT civ_id FROM towns WHERE id=2"));assertTrue(ConquestStore.conquered(db,2));
        }
    }
    @Test void aSingleCityRequiresAnActiveMotherlandAndMovesOnlyThatCity()throws Exception {
        try(var db=database()) {
            assertThrows(IllegalArgumentException.class,()->OccupationActionsStore.liberate(db,"Colony",leader,100));db.rollback();
            OccupationActionsStore.liberate(db,"Capital",leader,100);db.commit();
            var result=OccupationActionsStore.liberate(db,"Foreign",thirdLeader,101);db.commit();assertFalse(result.civilization());assertEquals(1,result.towns());assertEquals(2,value(db,"SELECT civ_id FROM towns WHERE id=4"));assertEquals(2,value(db,"SELECT civ_id FROM residents WHERE uuid='"+thirdLeader+"'"));
        }
    }
    @Test void activeWarAndTransactionRollbackNeverPartiallyReviveTheMotherland()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("INSERT INTO war_sessions VALUES(10,200,'ACTIVE')");db.commit();assertThrows(IllegalArgumentException.class,()->OccupationActionsStore.liberate(db,"Capital",leader,100));db.rollback();
            db.createStatement().execute("UPDATE war_sessions SET state='CLOSED'");db.commit();OccupationActionsStore.liberate(db,"Capital",leader,201);db.rollback();
            assertTrue(ConquestStore.conquered(db,2));assertEquals(1,value(db,"SELECT civ_id FROM towns WHERE id=2"));assertEquals(0,value(db,"SELECT COUNT(*) FROM occupation_actions"));
        }
    }
    @Test void missingCapitalFieldFallsBackToTheOriginalCapitolTemplate()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("UPDATE civilizations SET capital_town_id=NULL WHERE id=2");db.commit();assertTrue(OccupationActionsStore.liberate(db,"Capital",leader,100).civilization());db.commit();assertEquals(2,value(db,"SELECT civ_id FROM towns WHERE id=3"));
        }
    }
}
