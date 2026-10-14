package com.avrgaming.civcraft.modern.war;

import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WarRespawnStoreTest {
    final BuildingDamageStoreTest fixture=new BuildingDamageStoreTest();
    Connection database()throws Exception {
        var db=fixture.database();
        try(var s=db.createStatement()) {
            s.execute("ALTER TABLE civilizations ADD COLUMN capital_town_id INTEGER");
            s.execute("UPDATE civilizations SET capital_town_id=2 WHERE id=2");
            s.execute("UPDATE structure_builds SET structure_id='s_capitol'");
        }
        WarRespawnStore.install(db);
        WarRespawnStore.register(db,new WarRespawnStore.Point(1,"respawn","world",5,90,8));
        WarRespawnStore.register(db,new WarRespawnStore.Point(1,"revive","world",10,80,12));
        db.commit();return db;
    }
    @Test void onlyWarringTownMembersRespawnAtTheirCurrentCapitalDuringBattle()throws Exception {
        try(var db=database()) {
            var room=WarRespawnStore.room(db,fixture.owner,150);assertEquals(1,room.size());assertEquals("respawn",room.getFirst().kind());
            assertTrue(WarRespawnStore.room(db,fixture.homeless,150).isEmpty());
            assertTrue(WarRespawnStore.room(db,fixture.owner,99).isEmpty());assertTrue(WarRespawnStore.room(db,fixture.owner,200).isEmpty());
            db.createStatement().execute("UPDATE diplomacy_relations SET status='NEUTRAL'");db.commit();assertTrue(WarRespawnStore.room(db,fixture.owner,150).isEmpty());
        }
    }
    @Test void formerCapitalAndIncompleteBuildingNeverSupplyAnotherNationsRoom()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("INSERT INTO towns VALUES(3,2)");db.createStatement().execute("UPDATE civilizations SET capital_town_id=3 WHERE id=2");db.commit();assertTrue(WarRespawnStore.room(db,fixture.owner,150).isEmpty());
            db.createStatement().execute("UPDATE civilizations SET capital_town_id=2 WHERE id=2");db.createStatement().execute("UPDATE structure_builds SET status='BUILDING'");db.commit();assertTrue(WarRespawnStore.room(db,fixture.owner,150).isEmpty());
            db.createStatement().execute("UPDATE structure_builds SET status='COMPLETE'");db.createStatement().execute("UPDATE towns SET civ_id=1 WHERE id=2");db.commit();assertTrue(WarRespawnStore.room(db,fixture.owner,150).isEmpty());
        }
    }
    @Test void ArrivalTimeIsUpdatedOncePerRespawnAndRollbackKeepsPreviousTime()throws Exception {
        try(var db=database()) {
            assertNull(WarRespawnStore.lastKilled(db,fixture.owner));WarRespawnStore.recordArrival(db,fixture.owner,150);db.commit();assertEquals(150L,WarRespawnStore.lastKilled(db,fixture.owner));
            WarRespawnStore.recordArrival(db,fixture.owner,180);db.rollback();assertEquals(150L,WarRespawnStore.lastKilled(db,fixture.owner));
            WarRespawnStore.recordArrival(db,fixture.owner,190);db.commit();assertEquals(190L,WarRespawnStore.lastKilled(db,fixture.owner));
        }
    }
    @Test void duplicateDiscoveryIsIdempotentAndDeletedBuildingsRemoveTheirPoints()throws Exception {
        try(var db=database()) {
            WarRespawnStore.register(db,new WarRespawnStore.Point(1,"respawn","world",5,90,8));db.commit();assertEquals(1,WarRespawnStore.room(db,fixture.owner,150).size());
            db.createStatement().execute("DELETE FROM structure_builds WHERE id=1");db.commit();assertTrue(WarRespawnStore.room(db,fixture.owner,150).isEmpty());
            try(var r=db.createStatement().executeQuery("SELECT COUNT(*) FROM war_respawn_points")){r.next();assertEquals(0,r.getInt(1));}
        }
    }
}
