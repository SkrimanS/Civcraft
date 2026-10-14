package com.avrgaming.civcraft.modern.war;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WarCampStoreTest {
    final BuildingDamageStoreTest f=new BuildingDamageStoreTest();
    Connection database()throws Exception {
        var db=f.database();db.createStatement().execute("ALTER TABLE civilizations ADD COLUMN leader_uuid TEXT");db.createStatement().execute("UPDATE civilizations SET leader_uuid='"+f.attacker+"' WHERE id=1");db.createStatement().execute("UPDATE civilizations SET leader_uuid='"+f.owner+"' WHERE id=2");db.createStatement().execute("CREATE TABLE civ_advisors(civ_id INTEGER,resident_uuid TEXT)");WarCampStore.install(db);db.commit();return db;
    }
    List<WarCampStore.Block> blocks(int x){return List.of(new WarCampStore.Block("world",x,80,0,"minecraft:stone","minecraft:oak_planks"));}
    WarCampStore.Camp prepare(Connection db,String request,int x)throws Exception {return WarCampStore.prepare(db,request,f.owner,"world",x,80,0,blocks(x),3,0,150);}
    @Test void leaderOrAdvisorWithTownCanBuildOnlyInsideTheBattleWindow()throws Exception {
        try(var db=database()) {
            assertEquals(2,WarCampStore.authority(db,f.owner,150).civilization());assertThrows(IllegalArgumentException.class,()->WarCampStore.authority(db,f.owner,99));assertThrows(IllegalArgumentException.class,()->WarCampStore.authority(db,f.owner,200));assertThrows(IllegalArgumentException.class,()->WarCampStore.authority(db,f.homeless,150));
            db.createStatement().execute("UPDATE residents SET town_id=2,civ_id=2 WHERE uuid='"+f.homeless+"'");db.commit();assertThrows(IllegalArgumentException.class,()->WarCampStore.authority(db,f.homeless,150));
            db.createStatement().execute("INSERT INTO civ_advisors VALUES(2,'"+f.homeless+"')");db.commit();assertEquals(2,WarCampStore.authority(db,f.homeless,150).civilization());
        }
    }
    @Test void preparationPreservesBeforeAfterImagesAndReplaysWithoutDuplicatingTheCamp()throws Exception {
        try(var db=database()) {
            var camp=prepare(db,"one",0);db.commit();assertEquals("PREPARED",camp.state());assertEquals(blocks(0),WarCampStore.blocks(db,camp.id()));assertEquals(camp.id(),prepare(db,"one",0).id());
            var rolled=prepare(db,"rollback",1);db.rollback();assertThrows(SQLException.class,()->WarCampStore.camp(db,rolled.id()));
            WarCampStore.activate(db,camp.id(),155);db.commit();WarCampStore.activate(db,camp.id(),160);db.commit();assertEquals("ACTIVE",WarCampStore.camp(db,camp.id()).state());
            try(var r=db.createStatement().executeQuery("SELECT built_at FROM war_camp_cooldowns WHERE civ_id=2")){r.next();assertEquals(155,r.getLong(1));}
        }
    }
    @Test void maximumAndOverlapIncludePreparedCampsButDestroyedCampsReleaseTheSlot()throws Exception {
        try(var db=database()) {
            var first=prepare(db,"one",0);prepare(db,"two",1);prepare(db,"three",2);db.commit();assertThrows(IllegalArgumentException.class,()->prepare(db,"four",3));db.rollback();
            WarCampStore.activate(db,first.id(),155);WarCampStore.destroyed(db,first.id(),160);db.commit();assertDoesNotThrow(()->prepare(db,"replacement",0));db.rollback();
            assertThrows(IllegalArgumentException.class,()->WarCampStore.prepare(db,"overlap",f.attacker,"world",1,80,0,blocks(1),3,0,150));db.rollback();
        }
    }
    @Test void rebuildCooldownMatchesTheOriginalWholeMinuteTruncationAndResetsOnDestruction()throws Exception {
        assertEquals(0,WarCampStore.cooldownMinutes(null,30,1000));assertEquals(30,WarCampStore.cooldownMinutes(1000L,30,1000));assertEquals(1,WarCampStore.cooldownMinutes(1000L,30,1741000));assertEquals(0,WarCampStore.cooldownMinutes(1000L,30,1741001));assertEquals(0,WarCampStore.cooldownMinutes(1000L,30,1801000));
        try(var db=database()) {
            var camp=prepare(db,"one",0);WarCampStore.activate(db,camp.id(),151);db.commit();assertThrows(IllegalArgumentException.class,()->WarCampStore.prepare(db,"blocked",f.owner,"world",1,80,0,blocks(1),3,30,152));db.rollback();
            WarCampStore.destroyed(db,camp.id(),160);db.commit();try(var r=db.createStatement().executeQuery("SELECT built_at FROM war_camp_cooldowns WHERE civ_id=2")){r.next();assertEquals(160,r.getLong(1));}
        }
    }
    @Test void controlDamageIsAuthorizedIdempotentAndDestroysCampWithoutDefeatingATown()throws Exception {
        try(var db=database()) {
            var camp=prepare(db,"one",0);WarCampStore.point(db,camp.id(),"control","world",0,81,0,20);WarCampStore.point(db,camp.id(),"control","world",1,81,0,20);WarCampStore.activate(db,camp.id(),151);db.commit();
            assertThrows(IllegalArgumentException.class,()->WarCampStore.hit(db,"own",f.owner,camp.id(),"world",0,81,0,1,152));db.rollback();assertThrows(IllegalArgumentException.class,()->WarCampStore.hit(db,"homeless",f.homeless,camp.id(),"world",0,81,0,1,152));db.rollback();
            assertEquals(19,WarCampStore.hit(db,"hit",f.attacker,camp.id(),"world",0,81,0,1,152).health());db.commit();assertEquals(19,WarCampStore.hit(db,"hit",f.attacker,camp.id(),"world",0,81,0,1,153).health());
            assertFalse(WarCampStore.hit(db,"first",f.attacker,camp.id(),"world",0,81,0,20,154).destroyed());assertTrue(WarCampStore.hit(db,"last",f.attacker,camp.id(),"world",1,81,0,20,155).destroyed());db.commit();assertEquals("DESTROYED",WarCampStore.camp(db,camp.id()).state());
            try(var r=db.createStatement().executeQuery("SELECT COUNT(*) FROM war_defeated_towns")){r.next();assertEquals(0,r.getInt(1));}try(var r=db.createStatement().executeQuery("SELECT COUNT(*) FROM war_inactive_towns")){r.next();assertEquals(0,r.getInt(1));}
        }
    }
    @Test void failedHitRollbackAndDefeatedAttackerCannotDamageCamp()throws Exception {
        try(var db=database()) {
            var camp=prepare(db,"one",0);WarCampStore.point(db,camp.id(),"control","world",0,81,0,20);WarCampStore.activate(db,camp.id(),151);db.commit();
            WarCampStore.hit(db,"rollback",f.attacker,camp.id(),"world",0,81,0,20,152);db.rollback();assertEquals("ACTIVE",WarCampStore.camp(db,camp.id()).state());assertEquals(19,WarCampStore.hit(db,"one",f.attacker,camp.id(),"world",0,81,0,1,153).health());db.commit();
            db.createStatement().execute("INSERT INTO war_inactive_towns VALUES(100,1)");db.commit();assertThrows(IllegalArgumentException.class,()->WarCampStore.hit(db,"defeated",f.attacker,camp.id(),"world",0,81,0,1,154));db.rollback();assertThrows(IllegalArgumentException.class,()->WarCampStore.hit(db,"late",f.attacker,camp.id(),"world",0,81,0,1,200));db.rollback();
        }
    }
    @Test void endOfWarSchedulesReverseOrderRestorationEvenForUnfinishedOrDestroyedCamps()throws Exception {
        try(var db=database()) {
            var first=prepare(db,"one",0);WarCampStore.activate(db,first.id(),151);WarCampStore.destroyed(db,first.id(),152);db.commit();var second=prepare(db,"two",0);db.commit();
            WarCampStore.scheduleRestoration(db,199);db.commit();assertNull(WarCampStore.nextRestoration(db));WarCampStore.scheduleRestoration(db,200);db.commit();assertEquals(second.id(),WarCampStore.nextRestoration(db));
            assertThrows(IllegalArgumentException.class,()->WarCampStore.restored(db,first.id(),201));db.rollback();WarCampStore.restored(db,second.id(),201);db.commit();assertEquals(first.id(),WarCampStore.nextRestoration(db));WarCampStore.restored(db,first.id(),202);db.commit();assertNull(WarCampStore.nextRestoration(db));WarCampStore.restored(db,first.id(),203);db.commit();
        }
    }
    @Test void originalOwnerAndTerrainJournalSurviveTownTransferAndCivilizationDeletion()throws Exception {
        try(var db=database()) {
            var camp=prepare(db,"one",0);db.commit();db.createStatement().execute("UPDATE towns SET civ_id=1 WHERE id=2");db.createStatement().execute("DELETE FROM civilizations WHERE id=2");db.commit();assertEquals(2,WarCampStore.camp(db,camp.id()).civilization());assertEquals(blocks(0),WarCampStore.blocks(db,camp.id()));
            WarCampStore.scheduleRestoration(db,200);db.commit();assertEquals(camp.id(),WarCampStore.nextRestoration(db));
        }
    }
    @Test void campJournalAndControlDamageSurviveClosingAndReopeningTheDatabase(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)throws Exception {
        var file=directory.resolve("war-camps.db");long id;
        try(var db=database()) {
            var camp=prepare(db,"persistent",0);id=camp.id();WarCampStore.point(db,id,"control","world",0,81,0,20);WarCampStore.activate(db,id,151);WarCampStore.hit(db,"hit",f.attacker,id,"world",0,81,0,1,152);db.commit();db.setAutoCommit(true);
            assertEquals(0,db.unwrap(org.sqlite.SQLiteConnection.class).getDatabase().backup("main",file.toString(),null));
        }
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+file)) {
            assertEquals("ACTIVE",WarCampStore.camp(db,id).state());assertEquals(blocks(0),WarCampStore.blocks(db,id));assertEquals(19,WarCampStore.hit(db,"hit",f.attacker,id,"world",0,81,0,1,153).health());
            WarCampStore.scheduleRestoration(db,200);assertEquals(id,WarCampStore.nextRestoration(db));
        }
    }
}
