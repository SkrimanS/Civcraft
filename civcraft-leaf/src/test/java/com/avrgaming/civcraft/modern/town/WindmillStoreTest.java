package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import java.util.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WindmillStoreTest {
    @TempDir Path directory;
    void schema(Connection db)throws SQLException {
        db.createStatement().execute("PRAGMA foreign_keys=ON");db.createStatement().execute("CREATE TABLE towns(id INTEGER PRIMARY KEY,civ_id INTEGER)");db.createStatement().execute("INSERT INTO towns VALUES(1,1),(2,2)");
        db.createStatement().execute("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY,town_id INTEGER,world TEXT,x INTEGER,z INTEGER,structure_id TEXT,status TEXT)");db.createStatement().execute("INSERT INTO structure_builds VALUES(1,1,'world',0,0,'ti_windmill','COMPLETE'),(2,2,'world',-16,0,'ti_farm','COMPLETE'),(3,1,'world',0,0,'ti_windmill','COMPLETE')");
        db.createStatement().execute("CREATE TABLE war_sessions(state TEXT,start_at INTEGER,end_at INTEGER)");ProductionStore.install(db);db.setAutoCommit(false);
    }
    WindmillStore.Bed bed(){return new WindmillStore.Bed(new WindmillPlan.Spot(2,"world",-1,64,0),WindmillPlan.Crop.WHEAT,"minecraft:air");}
    @Test void cropIntentSurvivesConnectionReopenAndCompletionReleasesEveryTarget()throws Exception {
        String url="jdbc:sqlite:"+directory.resolve("planting.db");
        try(var db=DriverManager.getConnection(url)){schema(db);var clock=ProductionStore.initialize(db,1,60000,10);WindmillStore.prepare(db,"plant",clock,11,List.of(bed()),new byte[]{42},100);db.commit();}
        try(var db=DriverManager.getConnection(url)) {
            db.setAutoCommit(false);assertEquals(List.of(bed()),WindmillStore.beds(db,"plant"));assertEquals("minecraft:wheat[age=0]",WindmillStore.beds(db,"plant").getFirst().after());
            assertThrows(IllegalArgumentException.class,()->ProductionStore.requireBuildUnlocked(db,2));assertThrows(IllegalArgumentException.class,()->ProductionStore.requireTownUnlocked(db,2));
            assertThrows(SQLException.class,()->db.createStatement().execute("DELETE FROM structure_builds WHERE id=2"));assertThrows(SQLException.class,()->db.createStatement().execute("UPDATE structure_builds SET town_id=1 WHERE id=2"));assertThrows(SQLException.class,()->db.createStatement().execute("UPDATE towns SET civ_id=1 WHERE id=2"));db.rollback();
            assertTrue(ProductionStore.complete(db,"plant"));db.commit();assertTrue(WindmillStore.beds(db,"plant").isEmpty());assertFalse(ProductionStore.complete(db,"plant"));db.commit();ProductionStore.requireBuildUnlocked(db,2);ProductionStore.requireTownUnlocked(db,2);
            db.createStatement().execute("DELETE FROM structure_builds WHERE id=2");db.commit();
        }
    }
    @Test void competingMillsCannotReserveOneBedAndRejectedTransactionDoesNotLeaveAnIntent()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);var first=ProductionStore.initialize(db,1,60000,10);var other=ProductionStore.initialize(db,3,60000,10);
            WindmillStore.prepare(db,"first",first,11,List.of(bed()),new byte[]{42},100);db.commit();
            assertThrows(SQLException.class,()->WindmillStore.prepare(db,"other",other,11,List.of(bed()),new byte[]{43},100));db.rollback();
            assertEquals(1,ProductionStore.pending(db).size());assertTrue(WindmillStore.beds(db,"other").isEmpty());assertEquals(List.of(bed()),WindmillStore.beds(db,"first"));
        }
    }
    @Test void warAndInvalidFarmAssociationAreRejectedBeforeInventoryIntent()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);var clock=ProductionStore.initialize(db,1,60000,10);db.commit();
            db.createStatement().execute("INSERT INTO war_sessions VALUES('ACTIVE',0,1000)");db.commit();assertThrows(IllegalArgumentException.class,()->WindmillStore.prepare(db,"war",clock,11,List.of(bed()),new byte[]{42},100));db.rollback();assertTrue(ProductionStore.pending(db).isEmpty());
            db.createStatement().execute("DELETE FROM war_sessions");db.commit();var invalid=new WindmillStore.Bed(new WindmillPlan.Spot(2,"world",16,64,0),WindmillPlan.Crop.CARROTS,"minecraft:air");
            assertThrows(IllegalArgumentException.class,()->WindmillStore.prepare(db,"wrong-farm",clock,11,List.of(invalid),new byte[]{42},100));db.rollback();assertTrue(ProductionStore.pending(db).isEmpty());
            WindmillStore.prepare(db,"valid",clock,11,List.of(bed()),new byte[]{42},100);db.rollback();assertTrue(ProductionStore.pending(db).isEmpty());assertTrue(WindmillStore.beds(db,"valid").isEmpty());
        }
    }
}
