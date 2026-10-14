package com.avrgaming.civcraft.modern.town;
import java.io.File;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MineProductionStoreTest {
    ConsumptionCatalog catalog(){return new ConsumptionCatalog(new File("src/main/resources/civcraft/data/structures.yml"));}
    Connection database()throws Exception {
        var db=DriverManager.getConnection("jdbc:sqlite::memory:");
        try(var s=db.createStatement()) {
            s.execute("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY,town_id INTEGER,structure_id TEXT,status TEXT)");
            s.execute("CREATE TABLE building_consumption(build_id INTEGER PRIMARY KEY,level INTEGER,count INTEGER,result TEXT)");
            s.execute("INSERT INTO structure_builds VALUES(1,1,'ti_mine','COMPLETE'),(2,1,'ti_mine','COMPLETE'),(3,1,'ti_mine','BUILDING'),(4,1,'ti_mine','CANCELLED'),(5,2,'ti_mine','COMPLETE'),(6,1,'s_temple','COMPLETE'),(7,1,'ti_mine','COMPLETE')");
            s.execute("INSERT INTO building_consumption VALUES(1,1,0,'STAGNATE'),(2,2,12,'STARVE'),(3,5,40,'GROW'),(4,5,40,'GROW'),(5,5,40,'GROW')");
        }return db;
    }
    @Test void onlyCompletedTownMinesContributeAndMissingStateStartsAtLevelOne()throws Exception {
        try(var db=database()) {
            assertEquals(500,MineProductionStore.hammers(db,1,catalog()));
            assertEquals(400,MineProductionStore.hammers(db,2,catalog()));
            assertEquals(0,MineProductionStore.hammers(db,99,catalog()));
        }
    }
    @Test void starvingMinesKeepTheirLevelBonusAndLevelChangeImmediatelyChangesIt()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("UPDATE building_consumption SET count=0,result='STARVE' WHERE build_id=2");
            assertEquals(500,MineProductionStore.hammers(db,1,catalog()));
            db.createStatement().execute("UPDATE building_consumption SET level=5 WHERE build_id=2");
            assertEquals(700,MineProductionStore.hammers(db,1,catalog()));
        }
    }
    @Test void unknownMineLevelFailsInsteadOfProducingAnInventedBonus()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("UPDATE building_consumption SET level=6 WHERE build_id=2");
            assertThrows(SQLException.class,()->MineProductionStore.hammers(db,1,catalog()));
        }
    }
}
