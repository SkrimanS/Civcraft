package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BuildingRuinStoreTest {
    final BuildingDamageStoreTest fixture=new BuildingDamageStoreTest();
    final BuildingRuinStore.Change change=new BuildingRuinStore.Change("world",1,80,1,"minecraft:stone","minecraft:gravel",false);
    void destroy(Connection db)throws Exception {fixture.hit(db,"lethal",fixture.attacker,5,150);db.commit();}
    @Test void savedPlanIsImmutableAndCompletionIsIdempotent()throws Exception {
        try(var db=fixture.database()) {
            destroy(db);assertEquals(1L,BuildingRuinStore.next(db));assertNull(BuildingRuinStore.changes(db,1));
            BuildingRuinStore.prepare(db,1,List.of(change));db.commit();
            assertEquals(List.of(change),BuildingRuinStore.changes(db,1));
            assertThrows(SQLException.class,()->BuildingRuinStore.prepare(db,1,List.of()));db.rollback();
            assertEquals(List.of(change),BuildingRuinStore.changes(db,1));
            assertTrue(BuildingRuinStore.complete(db,1));db.commit();
            assertFalse(BuildingRuinStore.complete(db,1));db.commit();assertNull(BuildingRuinStore.next(db));
            assertEquals("DESTROYED",BuildingDamageStore.health(db,1).phase());
            assertEquals(0,BuildingDamageStore.health(db,1).hitpoints());
            try(var r=db.createStatement().executeQuery("SELECT status FROM structure_builds WHERE id=1")){assertTrue(r.next());assertEquals("WAR_DESTROYED",r.getString(1));}
        }
    }
    @Test void foreignCoordinatesAndDuplicateCoordinatesRollBackTheWholePlan()throws Exception {
        try(var db=fixture.database()) {
            destroy(db);var foreign=new BuildingRuinStore.Change("world",2,80,1,"stone","air",false);
            assertThrows(SQLException.class,()->BuildingRuinStore.prepare(db,1,List.of(change,foreign)));db.rollback();assertNull(BuildingRuinStore.changes(db,1));
            assertThrows(SQLException.class,()->BuildingRuinStore.prepare(db,1,List.of(change,change)));db.rollback();assertNull(BuildingRuinStore.changes(db,1));
            assertEquals("DESTROY_PENDING",BuildingDamageStore.health(db,1).phase());
        }
    }
    @Test void completionFailureDoesNotLoseThePendingJob()throws Exception {
        try(var db=fixture.database()) {
            destroy(db);BuildingRuinStore.prepare(db,1,List.of(change));db.commit();
            db.createStatement().execute("UPDATE structure_builds SET status='COMPLETE' WHERE id=1");db.commit();
            assertThrows(SQLException.class,()->BuildingRuinStore.complete(db,1));db.rollback();
            assertEquals("DESTROY_PENDING",BuildingDamageStore.health(db,1).phase());
            try(var r=db.createStatement().executeQuery("SELECT state FROM building_ruin_jobs")){assertTrue(r.next());assertEquals("PREPARED",r.getString(1));}
        }
    }
    @Test void legacyHealthSchemaMigrationPreservesDamage()throws Exception {
        try(var db=fixture.database()) {
            db.createStatement().execute("DROP TABLE building_combat_health");
            db.createStatement().execute("CREATE TABLE building_combat_health(build_id INTEGER PRIMARY KEY REFERENCES structure_builds(id),hitpoints INTEGER,maximum INTEGER,phase TEXT CHECK(phase IN ('DAMAGED','DESTROY_PENDING')))");
            db.createStatement().execute("INSERT INTO building_combat_health VALUES(1,3,5,'DAMAGED')");db.commit();
            BuildingDamageStore.install(db);db.commit();
            assertEquals(3,BuildingDamageStore.health(db,1).hitpoints());
            destroy(db);BuildingRuinStore.prepare(db,1,List.of());assertTrue(BuildingRuinStore.complete(db,1));db.commit();
        }
    }
}
