package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BuildingRepairStoreTest {
    final BuildingDamageStoreTest fixture=new BuildingDamageStoreTest();
    Connection database()throws Exception {
        var db=fixture.database();try(var s=db.createStatement()) {
            s.execute("ALTER TABLE civilizations ADD COLUMN leader_uuid TEXT");s.execute("UPDATE civilizations SET leader_uuid='"+fixture.owner+"' WHERE id=2");
            s.execute("ALTER TABLE towns ADD COLUMN mayor_uuid TEXT");s.execute("UPDATE towns SET mayor_uuid='"+fixture.owner+"' WHERE id=2");
            s.execute("ALTER TABLE towns ADD COLUMN coins INTEGER DEFAULT 1000");
            for(String field:new String[]{"cost INTEGER DEFAULT 501","template_path TEXT DEFAULT 'test.def'","world TEXT DEFAULT 'world'","x INTEGER DEFAULT 0","y INTEGER DEFAULT 80","z INTEGER DEFAULT 0"})s.execute("ALTER TABLE structure_builds ADD COLUMN "+field);
            s.execute("CREATE TABLE civ_technologies(civ_id INTEGER,tech_id TEXT)");s.execute("INSERT INTO civ_technologies VALUES(2,'tech_test')");
        }
        fixture.hit(db,"lethal",fixture.attacker,5,150);BuildingRuinStore.prepare(db,1,List.of());BuildingRuinStore.complete(db,1);
        db.createStatement().execute("UPDATE war_sessions SET state='CLOSED'");BuildingRepairStore.install(db);db.commit();return db;
    }
    long coins(Connection db)throws Exception {try(var r=db.createStatement().executeQuery("SELECT coins FROM towns WHERE id=2")){r.next();return r.getLong(1);}}
    List<BuildingRepairStore.Block> plan(){return List.of(new BuildingRepairStore.Block("world",1,80,1,"minecraft:stone"));}
    @Test void paymentAndPlanAreAtomicAndCompletionNeverChargesAgain()throws Exception {
        try(var db=database()) {
            long job=BuildingRepairStore.prepare(db,1,fixture.owner,"tech_test",plan(),250);db.commit();assertEquals(750,coins(db));
            assertEquals(plan(),BuildingRepairStore.blocks(db,job));assertEquals(job,BuildingRepairStore.next(db));
            assertThrows(IllegalArgumentException.class,()->BuildingRepairStore.prepare(db,1,fixture.owner,"tech_test",plan(),250));db.rollback();assertEquals(750,coins(db));
            assertEquals(1L,BuildingRepairStore.complete(db,job));db.commit();assertNull(BuildingRepairStore.complete(db,job));db.commit();
            assertEquals(750,coins(db));assertEquals(5,BuildingDamageStore.health(db,1).hitpoints());assertNull(BuildingRuinStore.changes(db,1));assertNull(BuildingRepairStore.next(db));
        }
    }
    @Test void permissionTechnologyWarAndInsufficientFundsCannotCharge()throws Exception {
        try(var db=database()) {
            assertThrows(IllegalArgumentException.class,()->BuildingRepairStore.prepare(db,1,fixture.attacker,"tech_test",plan(),250));db.rollback();
            assertThrows(IllegalArgumentException.class,()->BuildingRepairStore.prepare(db,1,fixture.owner,"missing",plan(),250));db.rollback();
            db.createStatement().execute("INSERT INTO war_sessions VALUES(250,350,'ACTIVE')");db.commit();
            assertThrows(IllegalArgumentException.class,()->BuildingRepairStore.prepare(db,1,fixture.owner,"tech_test",plan(),250));db.rollback();assertEquals(1000,coins(db));
            db.createStatement().execute("UPDATE war_sessions SET state='CLOSED'");db.createStatement().execute("UPDATE towns SET coins=249 WHERE id=2");db.commit();
            assertThrows(IllegalArgumentException.class,()->BuildingRepairStore.prepare(db,1,fixture.owner,"tech_test",plan(),350));db.rollback();assertEquals(249,coins(db));
        }
    }
    @Test void failedPlanInsertionRollsBackTheDebitAndJob()throws Exception {
        try(var db=database()) {
            var duplicate=List.of(plan().getFirst(),plan().getFirst());
            assertThrows(SQLException.class,()->BuildingRepairStore.prepare(db,1,fixture.owner,"tech_test",duplicate,250));db.rollback();
            assertEquals(1000,coins(db));assertNull(BuildingRepairStore.next(db));assertEquals("DESTROYED",BuildingDamageStore.health(db,1).phase());
        }
    }
    @Test void aRepairedBuildingCanBeDestroyedAndRepairedInTheNextWar()throws Exception {
        try(var db=database()) {
            long first=BuildingRepairStore.prepare(db,1,fixture.owner,"tech_test",plan(),250);BuildingRepairStore.complete(db,first);db.commit();
            db.createStatement().execute("INSERT INTO war_sessions VALUES(300,400,'ACTIVE')");fixture.hit(db,"second",fixture.attacker,5,350);
            BuildingRuinStore.prepare(db,1,List.of());BuildingRuinStore.complete(db,1);db.createStatement().execute("UPDATE war_sessions SET state='CLOSED'");db.commit();
            long second=BuildingRepairStore.prepare(db,1,fixture.owner,"tech_test",plan(),450);BuildingRepairStore.complete(db,second);db.commit();assertTrue(second>first);assertEquals(500,coins(db));
        }
    }
}
