package com.avrgaming.civcraft.modern.war;

import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HallValidationTest {
    final ControlPointStoreTest fixture=new ControlPointStoreTest();
    Connection database()throws Exception {
        var db=fixture.database();db.createStatement().execute("UPDATE structure_builds SET structure_id='s_capitol'");db.createStatement().execute("UPDATE civilizations SET capital_town_id=2 WHERE id=2");db.commit();return db;
    }
    @Test void foundationUsesLegacyWeightsAndActualDepthThresholds() {
        assertEquals(4,HallValidationRules.reinforcement("IRON_BLOCK"));assertEquals(3,HallValidationRules.reinforcement("CRACKED_STONE_BRICKS"));assertEquals(2,HallValidationRules.reinforcement("POLISHED_ANDESITE"));
        for(String material:new String[]{"AIR","CAVE_AIR","WATER","LAVA","COBWEB"})assertEquals(0,HallValidationRules.reinforcement(material));
        assertEquals(1,HallValidationRules.reinforcement("GLASS"));assertEquals(.8,HallValidationRules.requirement(10));assertEquals(.24,HallValidationRules.requirement(11));assertEquals(.24,HallValidationRules.requirement(41));
        assertTrue(HallValidationRules.layerValid(80,100,1));assertFalse(HallValidationRules.layerValid(79,100,1));assertTrue(HallValidationRules.layerValid(24,100,11));assertFalse(HallValidationRules.layerValid(23,100,11));assertTrue(HallValidationRules.layerValid(0,0,1));
    }
    @Test void missingHallAndFormerCapitalAreInvalidButCapturedFormerCapitalIsExemptFromLocationRule()throws Exception {
        try(var db=database()) {
            assertFalse(HallValidationStore.state(db,1).validated());HallValidationStore.save(db,1,true,"",1000);db.commit();assertTrue(HallValidationStore.state(db,1).valid());
            db.createStatement().execute("INSERT INTO towns VALUES(3,2)");db.commit();assertFalse(HallValidationStore.state(db,1).valid());assertTrue(HallValidationStore.state(db,1).reason().contains("без ратуши"));
            db.createStatement().execute("INSERT INTO structure_builds VALUES(2,3,'s_townhall','BUILDING',20,'Hall')");db.commit();assertTrue(HallValidationStore.state(db,1).valid());
            db.createStatement().execute("UPDATE civilizations SET capital_town_id=3 WHERE id=2");db.commit();assertFalse(HallValidationStore.state(db,1).valid());
            db.createStatement().execute("INSERT INTO town_conquests VALUES(2,1,1000)");db.commit();assertTrue(HallValidationStore.state(db,1).valid());
        }
    }
    @Test void validationStateRollsBackPersistsAndRejectsResultsForDemolishedOrIncompleteBuildings()throws Exception {
        try(var db=database()) {
            HallValidationStore.save(db,1,false,"Air beneath foundation",1000);db.commit();assertTrue(HallValidationStore.state(db,1).validated());assertFalse(HallValidationStore.state(db,1).valid());assertEquals(1000,HallValidationStore.state(db,1).checkedAt());
            HallValidationStore.save(db,1,true,"",2000);db.rollback();assertFalse(HallValidationStore.state(db,1).valid());
            db.createStatement().execute("UPDATE structure_builds SET status='BUILDING'");HallValidationStore.save(db,1,true,"",3000);db.commit();assertEquals(1000,HallValidationStore.state(db,1).checkedAt());
            db.createStatement().execute("DELETE FROM structure_builds WHERE id=1");db.commit();try(var r=db.createStatement().executeQuery("SELECT COUNT(*) FROM hall_validation")){r.next();assertEquals(0,r.getInt(1));}
        }
    }
    @Test void invalidAttackerCapitalStopsControlDamageWithoutChangingHealthOrAudit()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("INSERT INTO structure_builds VALUES(2,1,'s_capitol','COMPLETE',100,'Attacker capital')");db.createStatement().execute("UPDATE civilizations SET capital_town_id=1 WHERE id=1");HallValidationStore.save(db,2,false,"No foundation",120);db.commit();
            assertThrows(IllegalArgumentException.class,()->ControlPointStore.hit(db,"invalid",fixture.f.attacker,fixture.first,1,150));db.rollback();assertEquals(20,ControlPointStore.health(db,fixture.first,100));
            try(var r=db.createStatement().executeQuery("SELECT COUNT(*) FROM war_control_hits")){r.next();assertEquals(0,r.getInt(1));}
            HallValidationStore.save(db,2,true,"",130);db.commit();assertEquals(19,ControlPointStore.hit(db,"valid",fixture.f.attacker,fixture.first,1,150).health());db.commit();
        }
    }
    @Test void missingOrForeignCapitalCannotAuthorizeAnAttacker()throws Exception {
        try(var db=database()) {
            CapitalStore.seed(db);db.commit();assertTrue(HallValidationStore.capitalValid(db,1));
            db.createStatement().execute("UPDATE civilizations SET capital_town_id=2 WHERE id=1");db.commit();assertFalse(HallValidationStore.capitalValid(db,1));
            db.createStatement().execute("UPDATE civilizations SET capital_town_id=1 WHERE id=1");db.createStatement().execute("DELETE FROM structure_builds WHERE id=9000");db.commit();assertFalse(HallValidationStore.capitalValid(db,1));
            assertThrows(IllegalArgumentException.class,()->ControlPointStore.hit(db,"missing",fixture.f.attacker,fixture.first,1,150));db.rollback();assertEquals(20,ControlPointStore.health(db,fixture.first,100));
        }
    }
}
