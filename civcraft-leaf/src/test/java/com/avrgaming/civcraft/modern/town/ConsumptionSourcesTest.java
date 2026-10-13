package com.avrgaming.civcraft.modern.town;
import java.sql.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ConsumptionSourcesTest {
    Connection database()throws Exception {
        var db=DriverManager.getConnection("jdbc:sqlite::memory:");
        try(var s=db.createStatement()) {
            s.execute("CREATE TABLE structure_builds(id INTEGER,town_id INTEGER,structure_id TEXT,status TEXT)");
            s.execute("CREATE TABLE structure_chests(build_id INTEGER,chest_id INTEGER,world TEXT,x INTEGER,y INTEGER,z INTEGER)");
            s.execute("INSERT INTO structure_builds VALUES(1,1,'ti_cottage','COMPLETE'),(2,1,'s_granary','COMPLETE'),(3,1,'s_granary','COMPLETE'),(4,2,'s_granary','COMPLETE'),(5,1,'s_granary','CANCELLED'),(6,1,'s_temple','COMPLETE')");
            s.execute("INSERT INTO structure_chests VALUES(1,1,'world',1,80,1),(2,1,'world',2,80,1),(3,1,'world',3,80,1),(2,2,'world',4,80,1),(4,1,'world',5,80,1),(5,1,'world',6,80,1),(6,1,'world',7,80,1)");
        }return db;
    }
    @Test void cottageUsesAllActiveTownGranariesAndRejectsOwnAndForeignChests()throws Exception {
        try(var db=database()) {
            assertEquals(List.of(new ConsumptionContainers.Position("world",2,80,1),new ConsumptionContainers.Position("world",3,80,1)),ConsumptionSources.positions(db,1,1,"ti_cottage"));
        }
    }
    @Test void templeUsesOnlyItsOwnChests()throws Exception {
        try(var db=database()){assertEquals(List.of(new ConsumptionContainers.Position("world",7,80,1)),ConsumptionSources.positions(db,6,1,"s_temple"));}
    }
    @Test void noGranaryProducesEmptyInventoryForStarvationInsteadOfSkippingTheCottage()throws Exception {
        try(var db=database()){assertTrue(ConsumptionSources.positions(db,1,3,"ti_cottage").isEmpty());}
        var encoded=ConsumptionContainers.encode(List.of());assertEquals(8,encoded.length);assertEquals(List.of(),ConsumptionContainers.decode(encoded));
    }
}
