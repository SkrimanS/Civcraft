package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import java.util.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PastureStoreTest {
    @TempDir Path directory;
    private void schema(Connection db)throws SQLException{
        db.createStatement().execute("PRAGMA foreign_keys=ON");db.createStatement().execute("CREATE TABLE towns(id INTEGER PRIMARY KEY,civ_id INTEGER)");db.createStatement().execute("INSERT INTO towns VALUES(1,1),(2,2)");
        db.createStatement().execute("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY,town_id INTEGER,structure_id TEXT,status TEXT,max_hitpoints INTEGER)");db.createStatement().execute("INSERT INTO structure_builds VALUES(1,1,'ti_pasture','COMPLETE',500),(2,2,'ti_pasture','COMPLETE',500),(3,1,'ti_farm','COMPLETE',50)");
        db.createStatement().execute("CREATE TABLE structure_chunks(world TEXT,chunk_x INTEGER,chunk_z INTEGER,build_id INTEGER)");db.createStatement().execute("INSERT INTO structure_chunks VALUES('world',-1,10,1),('world',0,10,1),('nether',-1,10,2),('world',1,10,3)");
        db.createStatement().execute("CREATE TABLE building_combat_health(build_id INTEGER PRIMARY KEY,hitpoints INTEGER)");PastureStore.install(db);db.setAutoCommit(false);
    }
    private UUID reserve(Connection db,long pasture,String type,int maximum)throws SQLException{var uuid=UUID.randomUUID();PastureStore.reserve(db,uuid,pasture,"world",-1,10,type,maximum);return uuid;}
    @Test void boundChunksUseWorldAndOnlyCompletePastures()throws Exception{
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")){schema(db);assertEquals(1,PastureStore.at(db,"world",-1,10).orElseThrow());assertEquals(1,PastureStore.at(db,"world",0,10).orElseThrow());assertEquals(2,PastureStore.at(db,"nether",-1,10).orElseThrow());assertTrue(PastureStore.at(db,"world",1,10).isEmpty());
            db.createStatement().execute("UPDATE structure_builds SET status='WAR_DESTROYED' WHERE id=1");assertTrue(PastureStore.at(db,"world",-1,10).isEmpty());assertFalse(PastureStore.active(db,1));assertTrue(PastureStore.exists(db,1));
        }
    }
    @Test void mixedSpeciesAndUnconfirmedBirthsShareOneLimitAndDeathReleasesOneSlot()throws Exception{
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")){schema(db);var cow=reserve(db,1,"COW",2);var sheep=reserve(db,1,"SHEEP",2);db.commit();assertEquals(2,PastureStore.count(db,1));assertThrows(IllegalArgumentException.class,()->reserve(db,1,"CHICKEN",2));db.rollback();
            assertTrue(PastureStore.confirm(db,cow));assertFalse(PastureStore.confirm(db,cow));PastureStore.cancel(db,cow);assertNotNull(PastureStore.find(db,cow));PastureStore.cancel(db,sheep);assertEquals(1,PastureStore.count(db,1));
            var chicken=reserve(db,1,"CHICKEN",2);PastureStore.confirm(db,chicken);PastureStore.remove(db,cow);PastureStore.remove(db,cow);assertEquals(1,PastureStore.count(db,1));reserve(db,1,"RABBIT",2);db.commit();
        }
    }
    @Test void pendingBirthSurvivesReopenAndGuardsOwnershipUntilEntityIsSaved()throws Exception{
        String url="jdbc:sqlite:"+directory.resolve("pasture.db");UUID uuid;
        try(var db=DriverManager.getConnection(url)){schema(db);uuid=reserve(db,1,"COW",30);db.commit();}
        try(var db=DriverManager.getConnection(url)){db.setAutoCommit(false);assertEquals("PENDING",PastureStore.find(db,uuid).state());assertEquals(1,PastureStore.animals(db,true).size());
            assertThrows(SQLException.class,()->db.createStatement().execute("DELETE FROM structure_builds WHERE id=1"));assertThrows(SQLException.class,()->db.createStatement().execute("UPDATE structure_builds SET town_id=2 WHERE id=1"));assertThrows(SQLException.class,()->db.createStatement().execute("UPDATE structure_builds SET status='DEMOLISHED' WHERE id=1"));assertThrows(SQLException.class,()->db.createStatement().execute("UPDATE towns SET civ_id=2 WHERE id=1"));db.rollback();
            assertTrue(PastureStore.confirm(db,uuid));db.commit();assertTrue(PastureStore.animals(db,true).isEmpty());assertEquals(1,PastureStore.count(db,1));PastureStore.locate(db,uuid,"nether",-5,7);db.commit();assertEquals("nether",PastureStore.find(db,uuid).world());assertEquals(-5,PastureStore.find(db,uuid).chunkX());
            db.createStatement().execute("UPDATE structure_builds SET town_id=2 WHERE id=1");db.commit();assertEquals(1,PastureStore.find(db,uuid).pasture());
        }
    }
    @Test void restoredEntityTagsKeepMembershipAcrossUnloadsAndDemolitionReleasesIt()throws Exception{
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")){schema(db);var uuid=reserve(db,1,"PIG",30);PastureStore.confirm(db,uuid);PastureStore.remove(db,uuid);PastureStore.restore(db,uuid,1,"world",50,60,"PIG");PastureStore.restore(db,uuid,1,"world",51,60,"PIG");assertEquals(1,PastureStore.count(db,1));assertEquals(51,PastureStore.find(db,uuid).chunkX());
            assertThrows(SQLException.class,()->PastureStore.restore(db,uuid,2,"world",51,60,"PIG"));
            db.createStatement().execute("UPDATE structure_builds SET status='DEMOLISHED' WHERE id=1");PastureStore.releaseDemolished(db);assertEquals(0,PastureStore.count(db,1));PastureStore.restore(db,uuid,1,"world",51,60,"PIG");assertNull(PastureStore.find(db,uuid));db.commit();
        }
    }
    @Test void zeroHealthAndInvalidCapacityDenyBirthWithoutReservingAnAnimal()throws Exception{
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")){schema(db);db.createStatement().execute("INSERT INTO building_combat_health VALUES(1,0)");assertThrows(IllegalArgumentException.class,()->reserve(db,1,"COW",30));assertThrows(IllegalArgumentException.class,()->reserve(db,2,"COW",0));assertEquals(0,PastureStore.count(db,1));assertFalse(PastureRules.room(29,1,30));assertTrue(PastureRules.room(28,1,30));assertFalse(PastureRules.room(Long.MAX_VALUE,Long.MAX_VALUE,30));assertThrows(IllegalArgumentException.class,()->PastureRules.room(-1,0,30));assertTrue(PastureRules.controlled("MOOSHROOM"));assertFalse(PastureRules.controlled("WOLF"));assertTrue(PastureRules.horse("HORSE"));}
    }
}
