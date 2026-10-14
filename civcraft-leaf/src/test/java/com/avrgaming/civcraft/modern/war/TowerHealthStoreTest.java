package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TowerHealthStoreTest {
    final BuildingDamageStoreTest fixture=new BuildingDamageStoreTest();
    Connection database()throws Exception {
        var db=fixture.database();db.createStatement().execute("UPDATE structure_builds SET structure_id='s_arrowtower',max_hitpoints=100");TowerHealthStore.configure(db,.15);TowerHealthStore.initialize(db,1);db.commit();return db;
    }
    void wonder(Connection db,long town,String status)throws Exception{db.createStatement().execute("INSERT INTO structure_builds VALUES(2,"+town+",'w_chichen_itza','"+status+"',1,'Itza')");}
    @Test void allFourTowersUseCivilizationWideCompleteNonstackingBonus()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("INSERT INTO towns VALUES(3,2)");wonder(db,3,"BUILDING");assertEquals(100,BuildingDamageStore.health(db,1).maximum());
            db.createStatement().execute("UPDATE structure_builds SET status='COMPLETE' WHERE id=2");db.createStatement().execute("INSERT INTO structure_builds VALUES(3,3,'w_chichen_itza','COMPLETE',1,'Duplicate')");
            for(String type:TowerHealthStore.TYPES){db.createStatement().execute("UPDATE structure_builds SET structure_id='"+type+"' WHERE id=1");assertEquals(114,BuildingDamageStore.health(db,1).maximum());assertEquals(100,BuildingDamageStore.health(db,1).hitpoints());}
            db.createStatement().execute("UPDATE structure_builds SET structure_id='s_broadcast_tower' WHERE id=1");assertEquals(100,BuildingDamageStore.health(db,1).maximum());
            db.createStatement().execute("UPDATE structure_builds SET structure_id='s_arrowtower' WHERE id=1");db.createStatement().execute("UPDATE towns SET civ_id=1 WHERE id=3");assertEquals(100,BuildingDamageStore.health(db,1).maximum());
        }
    }
    @Test void bonusDoesNotHealDamageAndRestartDoesNotResetIt()throws Exception {
        try(var db=database()) {
            fixture.hit(db,"damage",fixture.attacker,30,150);wonder(db,2,"COMPLETE");assertEquals(70,BuildingDamageStore.health(db,1).hitpoints());assertEquals(114,BuildingDamageStore.health(db,1).maximum());db.commit();
            BuildingDamageStore.install(db);TowerHealthStore.preserveExisting(db);TowerHealthStore.configure(db,.15);TowerHealthStore.initialize(db,1);assertEquals(70,BuildingDamageStore.health(db,1).hitpoints());
            BuildingRegenerationStore.tick(db,Map.of(),2,(c,id)->true);assertEquals(72,BuildingDamageStore.health(db,1).hitpoints());
            db.createStatement().execute("UPDATE structure_builds SET status='DEMOLISHED' WHERE id=2");assertEquals(100,BuildingDamageStore.health(db,1).maximum());assertEquals(72,BuildingDamageStore.health(db,1).hitpoints());
        }
    }
    @Test void newlyCreatedTowerStartsAtBuffedCapacityAndRemovalPreservesHpUntilRegen()throws Exception {
        try(var db=database()) {
            wonder(db,2,"COMPLETE");db.createStatement().execute("DELETE FROM building_combat_health WHERE build_id=1");TowerHealthStore.initialize(db,1);assertEquals(114,BuildingDamageStore.health(db,1).hitpoints());
            db.createStatement().execute("UPDATE structure_builds SET status='DEMOLISHED' WHERE id=2");assertEquals(114,BuildingDamageStore.health(db,1).hitpoints());assertEquals(100,BuildingDamageStore.health(db,1).maximum());
            assertEquals(113,fixture.hit(db,"hit-after-loss",fixture.attacker,1,150).hitpoints());
            BuildingRegenerationStore.tick(db,Map.of("s_arrowtower",1),2,(c,id)->true);assertEquals(100,BuildingDamageStore.health(db,1).hitpoints());
        }
    }
    @Test void migrationPreservesRuinsAndOverCapacityHealthIsLegalAfterBonusLoss()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("DROP TABLE building_combat_health");db.createStatement().execute("CREATE TABLE building_combat_health(build_id INTEGER PRIMARY KEY REFERENCES structure_builds(id),hitpoints INTEGER,maximum INTEGER,phase TEXT CHECK(phase IN ('DAMAGED','DESTROY_PENDING','DESTROYED')),CHECK(hitpoints<=maximum))");db.createStatement().execute("INSERT INTO building_combat_health VALUES(1,0,100,'DESTROYED')");
            BuildingDamageStore.install(db);assertEquals("DESTROYED",BuildingDamageStore.health(db,1).phase());db.createStatement().execute("UPDATE building_combat_health SET hitpoints=114,maximum=100,phase='DAMAGED'");assertEquals(114,BuildingDamageStore.health(db,1).hitpoints());
            assertThrows(IllegalArgumentException.class,()->TowerHealthStore.maximum(100,Double.NaN));assertThrows(IllegalArgumentException.class,()->TowerHealthStore.maximum(Integer.MAX_VALUE,1));
        }
    }
}
