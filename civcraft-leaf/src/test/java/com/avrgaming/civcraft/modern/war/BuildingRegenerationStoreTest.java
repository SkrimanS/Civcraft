package com.avrgaming.civcraft.modern.war;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BuildingRegenerationStoreTest {
    final BuildingDamageStoreTest fixture=new BuildingDamageStoreTest();
    int hp(java.sql.Connection db)throws Exception{return BuildingDamageStore.health(db,1).hitpoints();}
    @Test void baseRegenRunsInBattleAndPeaceCapsHealthAndRollbackIsAtomic()throws Exception {
        try(var db=fixture.database()) {
            fixture.hit(db,"damage",fixture.attacker,3,150);db.commit();assertEquals(2,hp(db));
            assertEquals(1,BuildingRegenerationStore.tick(db,Map.of("s_grocer",1),2,(c,id)->true));assertEquals(3,hp(db));db.rollback();assertEquals(2,hp(db));
            db.createStatement().execute("UPDATE war_sessions SET state='CLOSED'");BuildingRegenerationStore.tick(db,Map.of("s_grocer",10),2,(c,id)->true);assertEquals(5,hp(db));
            assertEquals(0,BuildingRegenerationStore.tick(db,Map.of("s_grocer",10),2,(c,id)->true));
        }
    }
    @Test void wonderIsCivilizationWideCompletedOnlyAndNonstacking()throws Exception {
        try(var db=fixture.database()) {
            fixture.hit(db,"damage",fixture.attacker,4,150);
            db.createStatement().execute("INSERT INTO towns VALUES(3,2)");
            db.createStatement().execute("INSERT INTO structure_builds VALUES(2,3,'w_chichen_itza','BUILDING',1,'Itza'),(3,1,'w_chichen_itza','COMPLETE',1,'Foreign')");
            BuildingRegenerationStore.tick(db,Map.of(),2,(c,id)->true);assertEquals(1,hp(db));
            db.createStatement().execute("UPDATE structure_builds SET status='COMPLETE' WHERE id=2");
            db.createStatement().execute("INSERT INTO structure_builds VALUES(4,3,'w_chichen_itza','COMPLETE',1,'Duplicate')");
            BuildingRegenerationStore.tick(db,Map.of(),2,(c,id)->true);assertEquals(3,hp(db));
            db.createStatement().execute("UPDATE towns SET civ_id=1 WHERE id=3");BuildingRegenerationStore.tick(db,Map.of(),2,(c,id)->true);assertEquals(3,hp(db));
        }
    }
    @Test void invalidZeroAndDestroyedBuildingsNeverRegenerate()throws Exception {
        try(var db=fixture.database()) {
            fixture.hit(db,"damage",fixture.attacker,3,150);assertEquals(0,BuildingRegenerationStore.tick(db,Map.of("s_grocer",10),2,(c,id)->false));assertEquals(2,hp(db));
            fixture.hit(db,"lethal",fixture.attacker,2,150);assertEquals(0,BuildingRegenerationStore.tick(db,Map.of("s_grocer",10),2,(c,id)->true));assertEquals(0,hp(db));
            db.createStatement().execute("UPDATE structure_builds SET status='COMPLETE'");assertEquals(0,BuildingRegenerationStore.tick(db,Map.of("s_grocer",10),2,(c,id)->true));assertEquals(0,hp(db));
        }
    }
    @Test void nativeBaseAndWonderRatesAddAndWonderDestructionStopsItsRate()throws Exception {
        try(var db=fixture.database()) {
            db.createStatement().execute("UPDATE structure_builds SET max_hitpoints=100");fixture.hit(db,"damage",fixture.attacker,90,150);
            db.createStatement().execute("INSERT INTO structure_builds VALUES(2,2,'w_chichen_itza','COMPLETE',1,'Itza')");
            BuildingRegenerationStore.tick(db,Map.of("s_grocer",6),2,(c,id)->true);assertEquals(18,hp(db));
            db.createStatement().execute("UPDATE structure_builds SET status='DEMOLISHED' WHERE id=2");BuildingRegenerationStore.tick(db,Map.of("s_grocer",6),2,(c,id)->true);assertEquals(24,hp(db));
            assertThrows(IllegalArgumentException.class,()->BuildingRegenerationStore.tick(db,Map.of("s_grocer",-1),2,(c,id)->true));
        }
    }
}
