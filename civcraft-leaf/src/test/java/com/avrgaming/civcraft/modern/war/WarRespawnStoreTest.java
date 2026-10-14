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
            s.execute("ALTER TABLE civilizations ADD COLUMN leader_uuid TEXT");s.execute("UPDATE civilizations SET leader_uuid='"+fixture.owner+"' WHERE id=2");
            s.execute("ALTER TABLE towns ADD COLUMN mayor_uuid TEXT");s.execute("UPDATE towns SET mayor_uuid='"+fixture.owner+"' WHERE id=2");
            s.execute("ALTER TABLE towns ADD COLUMN name TEXT");s.execute("UPDATE towns SET name='Capital' WHERE id=2");
            for(String column:new String[]{"world TEXT","x INTEGER","y INTEGER","z INTEGER"})s.execute("ALTER TABLE structure_builds ADD COLUMN "+column);
            s.execute("UPDATE structure_builds SET world='world',x=0,y=80,z=0");
            s.execute("CREATE TABLE civ_advisors(civ_id INTEGER,resident_uuid TEXT)");
        }
        WarRespawnStore.install(db);
        WarRespawnStore.register(db,new WarRespawnStore.Point(1,"respawn","world",5,90,8));
        WarRespawnStore.register(db,new WarRespawnStore.Point(1,"revive","world",10,80,12));
        db.commit();return db;
    }
    @Test void selectionIsSharedAndOnlyMayorLeaderOrAdvisorCanChangeIt()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("INSERT INTO towns(id,civ_id,name) VALUES(3,2,'Second')");
            db.createStatement().execute("INSERT INTO structure_builds(id,town_id,structure_id,status,world,x,y,z) VALUES(2,3,'s_townhall','COMPLETE','world',40,80,0)");
            db.createStatement().execute("UPDATE residents SET town_id=2,civ_id=2 WHERE uuid='"+fixture.homeless+"'");db.commit();
            assertThrows(IllegalArgumentException.class,()->WarRespawnStore.cycle(db,1,fixture.homeless,1,150));db.rollback();
            WarRespawnStore.authorize(db,1,fixture.homeless,false,150);assertEquals(1,WarRespawnStore.cycle(db,1,fixture.owner,1,150));db.commit();assertEquals(1,WarRespawnStore.selected(db,1));
            assertEquals(0,WarRespawnStore.cycle(db,1,fixture.owner,1,150));assertEquals(0,WarRespawnStore.cycle(db,1,fixture.owner,-1,150));db.commit();
            db.createStatement().execute("INSERT INTO civ_advisors VALUES(2,'"+fixture.homeless+"')");db.commit();assertEquals(1,WarRespawnStore.cycle(db,1,fixture.homeless,1,150));db.commit();
            assertThrows(IllegalArgumentException.class,()->WarRespawnStore.authorize(db,1,fixture.attacker,false,150));
            assertThrows(IllegalArgumentException.class,()->WarRespawnStore.authorize(db,1,fixture.owner,false,200));db.rollback();
        }
    }
    @Test void defeatedOtherTownsAreExcludedButTheCapitalRemainsAvailable()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("INSERT INTO towns(id,civ_id,name) VALUES(3,2,'Second')");
            db.createStatement().execute("INSERT INTO structure_builds(id,town_id,structure_id,status,world,x,y,z) VALUES(2,3,'s_townhall','COMPLETE','world',40,80,0)");db.commit();assertEquals(2,WarRespawnStore.destinations(db,1,150).size());
            db.createStatement().execute("INSERT INTO war_inactive_towns VALUES(100,2),(100,3)");db.commit();var options=WarRespawnStore.destinations(db,1,150);assertEquals(1,options.size());assertEquals(2,options.getFirst().town());assertEquals(2,WarRespawnStore.destinations(db,1,200).size());
        }
    }
    @Test void respawnDelayUsesDestroyedControlsInTheCurrentWarAndTheExactDeadline()throws Exception {
        try(var db=database()) {
            ControlPointStore.install(db);db.createStatement().execute("INSERT INTO war_control_points VALUES(1,'world',1,80,1,100,'SANDSTONE')");
            db.createStatement().execute("INSERT INTO war_control_health VALUES(100,'world',1,80,1,0)");db.commit();
            assertEquals(1,WarRespawnStore.destroyedControls(db,1,150));assertEquals(0,WarRespawnStore.destroyedControls(db,1,200));
            assertEquals(60,WarRespawnStore.delaySeconds(30,30,1,false,10,0));assertEquals(660,WarRespawnStore.delaySeconds(30,30,1,true,10,0));
            assertEquals(1,WarRespawnStore.delaySeconds(30,30,1,false,10,100));
            assertEquals(1,WarRespawnStore.remainingMillis(1000L,60,60999));assertEquals(0,WarRespawnStore.remainingMillis(1000L,60,61000));assertEquals(0,WarRespawnStore.remainingMillis(null,60,1000));
        }
    }
    @Test void selectionRollbackAndSignDiscoveryPreserveDurableState()throws Exception {
        try(var db=database()) {
            var sign=new WarRespawnStore.SignPoint(1,"respawndata","world",2,90,8);WarRespawnStore.registerSign(db,sign);WarRespawnStore.registerSign(db,sign);WarRespawnStore.select(db,1,2);db.commit();
            assertEquals(sign,WarRespawnStore.sign(db,"world",2,90,8));WarRespawnStore.select(db,1,0);db.rollback();assertEquals(2,WarRespawnStore.selected(db,1));
            db.createStatement().execute("UPDATE structure_builds SET status='BUILDING'");db.commit();assertNull(WarRespawnStore.sign(db,"world",2,90,8));
        }
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
            db.createStatement().execute("INSERT INTO towns(id,civ_id) VALUES(3,2)");db.createStatement().execute("UPDATE civilizations SET capital_town_id=3 WHERE id=2");db.commit();assertTrue(WarRespawnStore.room(db,fixture.owner,150).isEmpty());
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
