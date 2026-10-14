package com.avrgaming.civcraft.modern.war;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TowerTargetStoreTest {
    final BuildingDamageStoreTest fixture=new BuildingDamageStoreTest();
    @Test void warAndHostileCanTargetWithoutBattleSessionButPeaceAlliesAndHomelessCannot()throws Exception {
        try(var db=fixture.database()) {
            OutlawStore.install(db);db.createStatement().execute("UPDATE war_sessions SET state='CLOSED'");assertTrue(TowerTargetStore.eligible(db,2,fixture.attacker,150));assertFalse(TowerTargetStore.eligible(db,2,fixture.owner,150));assertFalse(TowerTargetStore.eligible(db,2,fixture.homeless,150));
            for(String status:new String[]{"HOSTILE","NEUTRAL","PEACE","ALLY"}){db.createStatement().execute("UPDATE diplomacy_relations SET status='"+status+"'");assertEquals(status.equals("HOSTILE"),TowerTargetStore.eligible(db,2,fixture.attacker,150));}
        }
    }
    @Test void armedOutlawsCanBeHomelessAndFriendlyInterceptionIsSeparateFromTargeting()throws Exception {
        try(var db=fixture.database()) {
            OutlawStore.install(db);db.createStatement().execute("INSERT INTO town_outlaws VALUES(2,'"+fixture.homeless+"',0,100,0),(2,'"+fixture.owner+"',0,100,0)");
            assertFalse(TowerTargetStore.eligible(db,2,fixture.homeless,99));assertTrue(TowerTargetStore.eligible(db,2,fixture.homeless,100));assertTrue(TowerTargetStore.eligible(db,2,fixture.owner,150));assertTrue(TowerTargetStore.friendly(db,2,fixture.owner));assertFalse(TowerTargetStore.friendly(db,2,fixture.homeless));
        }
    }
    @Test void lighthouseRangeIsLocalCompleteAndNonstacking()throws Exception {
        try(var db=fixture.database()) {
            db.createStatement().execute("INSERT INTO structure_builds VALUES(2,1,'w_great_lighthouse','COMPLETE',1,'Foreign'),(3,2,'w_great_lighthouse','BUILDING',1,'Building')");assertEquals(100,TowerTargetStore.range(db,2,100,1.5));
            db.createStatement().execute("UPDATE structure_builds SET status='COMPLETE' WHERE id=3");db.createStatement().execute("INSERT INTO structure_builds VALUES(4,2,'w_great_lighthouse','COMPLETE',1,'Duplicate')");assertEquals(150,TowerTargetStore.range(db,2,100,1.5));
            db.createStatement().execute("UPDATE structure_builds SET status='DEMOLISHED' WHERE id IN (3,4)");assertEquals(100,TowerTargetStore.range(db,2,100,1.5));
        }
    }
    @Test void rangeUsesThreeDimensionalDistanceWithInclusiveOuterAndExclusiveInnerBoundary() {
        assertFalse(TowerTargetStore.range(25,5,100));assertTrue(TowerTargetStore.range(25.01,5,100));assertTrue(TowerTargetStore.range(10000,5,100));assertFalse(TowerTargetStore.range(10000.01,5,100));assertFalse(TowerTargetStore.range(Double.NaN,5,100));
    }
}
