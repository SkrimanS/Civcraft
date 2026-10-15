package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import java.util.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FarmGrowthStoreTest {
    @TempDir Path directory;
    private void schema(Connection db)throws SQLException{
        db.createStatement().execute("PRAGMA foreign_keys=ON");db.createStatement().execute("CREATE TABLE towns(id INTEGER PRIMARY KEY,civ_id INTEGER)");db.createStatement().execute("INSERT INTO towns VALUES(1,1),(2,2)");
        db.createStatement().execute("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY,town_id INTEGER,world TEXT,x INTEGER,z INTEGER,structure_id TEXT,status TEXT)");db.createStatement().execute("INSERT INTO structure_builds VALUES(1,1,'world',-16,160,'ti_farm','COMPLETE'),(2,2,'world',0,160,'ti_windmill','COMPLETE')");
        db.createStatement().execute("CREATE TABLE war_sessions(state TEXT,start_at INTEGER,end_at INTEGER)");ProductionStore.install(db);db.setAutoCommit(false);
    }
    private FarmGrowthStore.Cell cell(){return new FarmGrowthStore.Cell(new FarmGrowthPlan.Position("world",-1,-20,160),"minecraft:wheat[age=0]","minecraft:wheat[age=1]");}
    @Test void wallClockPersistsUnloadedCreditButDoesNotAccrueShutdownTime()throws Exception{
        String url="jdbc:sqlite:"+directory.resolve("growth.db");
        try(var db=DriverManager.getConnection(url)){schema(db);assertEquals(0,FarmGrowthStore.observe(db,1,60000,10).missed());assertEquals(3,FarmGrowthStore.observe(db,1,60000,13).missed());db.commit();}
        try(var db=DriverManager.getConnection(url)){db.setAutoCommit(false);assertEquals(3,FarmGrowthStore.state(db,1).missed());FarmGrowthStore.start(db,60000,1000);assertEquals(3,FarmGrowthStore.state(db,1).missed());assertEquals(4,FarmGrowthStore.observe(db,1,60000,1001).missed());assertEquals(4,FarmGrowthStore.observe(db,1,60000,999).missed());db.commit();}
    }
    @Test void preparedBlockImagesSurviveReopenAndDebitExactlyOneCycle()throws Exception{
        String url="jdbc:sqlite:"+directory.resolve("pending.db");
        try(var db=DriverManager.getConnection(url)){schema(db);FarmGrowthStore.observe(db,1,60000,10);FarmGrowthStore.observe(db,1,60000,13);FarmGrowthStore.prepare(db,"grow",1,List.of(cell()),new byte[]{1});db.commit();}
        try(var db=DriverManager.getConnection(url)){db.setAutoCommit(false);assertEquals(List.of(cell()),FarmGrowthStore.cells(db,"grow"));assertEquals(3,FarmGrowthStore.state(db,1).missed());
            assertThrows(IllegalArgumentException.class,()->ProductionStore.requireBuildUnlocked(db,1));assertThrows(SQLException.class,()->db.createStatement().execute("DELETE FROM structure_builds WHERE id=1"));assertThrows(SQLException.class,()->db.createStatement().execute("UPDATE structure_builds SET town_id=2 WHERE id=1"));assertThrows(SQLException.class,()->db.createStatement().execute("UPDATE towns SET civ_id=2 WHERE id=1"));assertThrows(SQLException.class,()->FarmGrowthStore.start(db,60000,1000));db.rollback();
            assertTrue(ProductionStore.complete(db,"grow"));db.commit();assertEquals(2,FarmGrowthStore.state(db,1).missed());assertEquals(1,ProductionStore.clock(db,1).tick());assertTrue(FarmGrowthStore.cells(db,"grow").isEmpty());assertFalse(ProductionStore.complete(db,"grow"));assertEquals(2,FarmGrowthStore.state(db,1).missed());
            FarmGrowthStore.prepare(db,"empty",1,List.of(),new byte[]{1});assertTrue(ProductionStore.complete(db,"empty"));db.commit();assertEquals(1,FarmGrowthStore.state(db,1).missed());
        }
    }
    @Test void invalidTargetsAndWindmillConflictsRollbackWithoutConsumingCredit()throws Exception{
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")){schema(db);FarmGrowthStore.observe(db,1,60000,10);assertThrows(SQLException.class,()->FarmGrowthStore.prepare(db,"early",1,List.of(cell()),new byte[]{1}));FarmGrowthStore.observe(db,1,60000,11);db.commit();
            var outside=new FarmGrowthStore.Cell(new FarmGrowthPlan.Position("world",0,-20,160),cell().before(),cell().after());assertThrows(IllegalArgumentException.class,()->FarmGrowthStore.prepare(db,"outside",1,List.of(outside),new byte[]{1}));db.rollback();
            var bed=new WindmillStore.Bed(new WindmillPlan.Spot(1,"world",-1,-20,160),WindmillPlan.Crop.WHEAT,"minecraft:air");var mill=ProductionStore.initialize(db,2,60000,10);WindmillStore.prepare(db,"plant",mill,11,List.of(bed),new byte[]{1},100);db.commit();
            assertThrows(SQLException.class,()->FarmGrowthStore.prepare(db,"grow",1,List.of(cell()),new byte[]{1}));db.rollback();assertEquals(1,FarmGrowthStore.state(db,1).missed());ProductionStore.complete(db,"plant");db.commit();
            FarmGrowthStore.prepare(db,"grow",1,List.of(cell()),new byte[]{1});db.commit();assertThrows(SQLException.class,()->WindmillStore.prepare(db,"plant2",ProductionStore.clock(db,2),12,List.of(bed),new byte[]{1},100));db.rollback();assertEquals(1,ProductionStore.pending(db).size());
        }
    }
}
