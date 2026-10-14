package com.avrgaming.civcraft.modern.build;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StructureSpacingRulesTest {
    @Test void spacingIsInclusiveAtLimitAndUsesVerticalDistance(){assertThrows(IllegalArgumentException.class,()->StructureSpacingRules.requireDistance(0,0,0,130,0,0,130));assertDoesNotThrow(()->StructureSpacingRules.requireDistance(0,0,0,130,1,0,130));assertDoesNotThrow(()->StructureSpacingRules.requireDistance(0,0,0,0,131,0,130));}
    @Test void queriesAllTownsOfOwnerOnlySameWorldAndFamilyIncludingUnfinishedAndRuins()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            db.createStatement().execute("CREATE TABLE towns(id INTEGER,civ_id INTEGER)");db.createStatement().execute("INSERT INTO towns VALUES(1,1),(2,1),(3,2)");db.createStatement().execute("CREATE TABLE structure_builds(id INTEGER,town_id INTEGER,structure_id TEXT,world TEXT,status TEXT,template_path TEXT,x INTEGER,y INTEGER,z INTEGER)");
            db.createStatement().execute("INSERT INTO structure_builds VALUES(1,2,'s_cannonship','world','BUILDING','a.def',0,0,0),(2,2,'s_cannontower','world','WAR_DESTROYED','a.def',0,0,0),(3,3,'s_cannontower','world','COMPLETE','a.def',0,0,0),(4,1,'s_cannontower','nether','COMPLETE','a.def',0,0,0),(5,1,'s_teslatower','world','COMPLETE','a.def',0,0,0),(6,1,'s_cannontower','world','DEMOLISHED','a.def',0,0,0),(7,1,NULL,'world','COMPLETE','a.def',0,0,0)");
            assertEquals(java.util.List.of(1L,2L),StructureSpacingRules.existing(db,1,"s_cannontower","world").stream().map(StructureSpacingRules.Existing::id).toList());assertEquals(java.util.List.of(5L),StructureSpacingRules.existing(db,1,"s_teslatower","world").stream().map(StructureSpacingRules.Existing::id).toList());
        }
    }
}
