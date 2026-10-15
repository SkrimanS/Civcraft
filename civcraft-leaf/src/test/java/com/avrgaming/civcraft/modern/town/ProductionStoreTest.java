package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProductionStoreTest {
    @TempDir Path directory;
    void schema(Connection db)throws SQLException {
        db.createStatement().execute("PRAGMA foreign_keys=ON");
        db.createStatement().execute("CREATE TABLE towns(id INTEGER PRIMARY KEY,civ_id INTEGER)");db.createStatement().execute("INSERT INTO towns VALUES(1,1),(2,2)");
        db.createStatement().execute("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY,town_id INTEGER REFERENCES towns(id) ON DELETE CASCADE,structure_id TEXT,status TEXT)");db.createStatement().execute("INSERT INTO structure_builds VALUES(1,1,'ti_fish_hatchery','COMPLETE')");
        ProductionStore.install(db);db.setAutoCommit(false);
    }
    @Test void persistedIntentCompletesOnceAfterConnectionReopenAndClearsPayload()throws Exception {
        String url="jdbc:sqlite:"+directory.resolve("production.db");
        try(var db=DriverManager.getConnection(url)){schema(db);var clock=ProductionStore.initialize(db,1,60000,10);ProductionStore.prepare(db,"cycle",clock,11,4,new byte[]{42});db.commit();}
        try(var db=DriverManager.getConnection(url)) {
            db.setAutoCommit(false);var pending=ProductionStore.pending(db);assertEquals(1,pending.size());assertArrayEquals(new byte[]{42},pending.getFirst().containers());
            assertThrows(SQLException.class,()->ProductionStore.start(db,60000,20));
            assertTrue(ProductionStore.complete(db,"cycle"));db.commit();assertFalse(ProductionStore.complete(db,"cycle"));db.commit();
            assertTrue(ProductionStore.pending(db).isEmpty());assertEquals(11,ProductionStore.clock(db,1).tick());
            try(var r=db.createStatement().executeQuery("SELECT length(containers) FROM resource_production_operations")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
            ProductionStore.start(db,60000,20);db.commit();assertEquals(20,ProductionStore.clock(db,1).tick());
        }
    }
    @Test void pendingProductionBlocksDemolitionCaptureUpgradeAndDuplicateCycle()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);var clock=ProductionStore.initialize(db,1,60000,10);ProductionStore.prepare(db,"cycle",clock,11,1,new byte[]{42});db.commit();
            assertThrows(IllegalArgumentException.class,()->ProductionStore.requireBuildUnlocked(db,1));assertThrows(IllegalArgumentException.class,()->ProductionStore.requireTownUnlocked(db,1));
            assertThrows(IllegalArgumentException.class,()->ProductionStore.fisheryLevel(db,1,2));
            assertThrows(SQLException.class,()->db.createStatement().execute("DELETE FROM structure_builds WHERE id=1"));
            assertThrows(SQLException.class,()->db.createStatement().execute("UPDATE structure_builds SET town_id=2 WHERE id=1"));
            assertThrows(SQLException.class,()->db.createStatement().execute("UPDATE towns SET civ_id=2 WHERE id=1"));
            assertThrows(SQLException.class,()->ProductionStore.prepare(db,"duplicate",clock,11,1,new byte[]{43}));db.rollback();
            ProductionStore.complete(db,"cycle");db.commit();ProductionStore.fisheryLevel(db,1,4);ProductionStore.fisheryLevel(db,1,2);db.commit();assertEquals(4,ProductionStore.fisheryLevel(db,1));
            db.createStatement().execute("DELETE FROM towns WHERE id=1");db.commit();assertNull(ProductionStore.clock(db,1));
        }
    }
    @Test void rollbackLeavesClockAndInventoryIntentUntouchedAndRejectsStaleCycle()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);var clock=ProductionStore.initialize(db,1,60000,10);db.commit();
            ProductionStore.prepare(db,"rolled-back",clock,11,1,new byte[]{42});db.rollback();assertTrue(ProductionStore.pending(db).isEmpty());assertEquals(clock,ProductionStore.clock(db,1));
            ProductionStore.prepare(db,"cycle",clock,11,1,new byte[]{42});ProductionStore.complete(db,"cycle");db.commit();
            assertThrows(SQLException.class,()->ProductionStore.prepare(db,"stale",clock,12,1,new byte[]{42}));db.rollback();
            db.createStatement().execute("UPDATE structure_builds SET status='WAR_DESTROYED' WHERE id=1");db.commit();
            var current=ProductionStore.clock(db,1);assertThrows(SQLException.class,()->ProductionStore.prepare(db,"destroyed",current,12,1,new byte[]{42}));
        }
    }
}
