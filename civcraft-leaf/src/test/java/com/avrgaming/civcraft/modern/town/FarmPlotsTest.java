package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FarmPlotsTest {
    void schema(Connection db)throws SQLException {
        db.createStatement().execute("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY,world TEXT,x INTEGER,z INTEGER,town_id INTEGER,structure_id TEXT,status TEXT)");
    }
    @Test void eightNeighborsExcludeCenterAndDistantFarmsWithoutRestrictingTown()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);int id=0;for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)db.createStatement().execute("INSERT INTO structure_builds VALUES("+(++id)+",'world',"+(x*16)+","+(z*16)+","+id+",'ti_farm','COMPLETE')");
            db.createStatement().execute("INSERT INTO structure_builds VALUES(10,'world',32,0,1,'ti_farm','COMPLETE'),(11,'nether',-16,0,1,'ti_farm','COMPLETE')");
            var adjacent=FarmPlots.adjacent(db,"world",0,0);assertEquals(8,adjacent.size());
            assertEquals(List.of("-1,0","1,0","0,-1","0,1","1,1","-1,-1","-1,1","1,-1"),adjacent.stream().map(p->p.x()+","+p.z()).toList());
            assertTrue(adjacent.stream().allMatch(p->p.world().equals("world")));assertTrue(adjacent.stream().noneMatch(p->p.farm()==5 || p.farm()==10));
        }
    }
    @Test void negativeCornersUseFloorDivisionAndOnlyStandingCompletedFarmsAreConsidered()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);db.createStatement().execute("INSERT INTO structure_builds VALUES(1,'world',-17,-1,1,'ti_farm','COMPLETE'),(2,'world',-1,-1,1,'ti_farm','COMPLETE'),(3,'world',0,-1,1,'ti_farm','BUILDING'),(4,'world',-1,0,1,'ti_farm','WAR_DESTROYED'),(5,'world',-17,-1,2,'ti_farm','COMPLETE'),(6,'world',-1,-17,1,'ti_cottage','COMPLETE')");
            assertEquals(List.of(new FarmPlots.Plot(5,"world",-2,-1)),FarmPlots.adjacent(db,"world",-1,-1));
        }
    }
}
