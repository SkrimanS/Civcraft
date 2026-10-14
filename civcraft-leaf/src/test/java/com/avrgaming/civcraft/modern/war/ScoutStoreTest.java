package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ScoutStoreTest {
    final BuildingDamageStoreTest fixture=new BuildingDamageStoreTest();
    @Test void scoutsReportNeutralHostileAndWarButNotTheirOwnPeaceOrAllies()throws Exception {
        try(var db=fixture.database()) {
            ScoutStore.install(db);assertEquals(ScoutStore.Threat.WAR,ScoutStore.threat(db,2,fixture.attacker,150));assertNull(ScoutStore.threat(db,2,fixture.owner,150));
            for(String status:new String[]{"NEUTRAL","HOSTILE","PEACE","ALLY"}){db.createStatement().execute("UPDATE diplomacy_relations SET status='"+status+"'");var threat=ScoutStore.threat(db,2,fixture.attacker,150);if(status.equals("ALLY")||status.equals("PEACE"))assertNull(threat);else assertEquals(ScoutStore.Threat.valueOf(status),threat);}
            assertEquals(ScoutStore.Threat.NEUTRAL,ScoutStore.threat(db,2,fixture.homeless,150));
        }
    }
    @Test void outlawOverridesOwnCivilizationButWaitsUntilArmed()throws Exception {
        try(var db=fixture.database()) {
            ScoutStore.install(db);db.createStatement().execute("INSERT INTO town_outlaws VALUES(2,'"+fixture.owner+"',0,100,0)");assertNull(ScoutStore.threat(db,2,fixture.owner,99));assertEquals(ScoutStore.Threat.OUTLAW,ScoutStore.threat(db,2,fixture.owner,100));
        }
    }
    @Test void preferencePersistsAcrossInstallationAndOnlyTownResidentsReceiveReports()throws Exception {
        try(var db=fixture.database()) {
            ScoutStore.install(db);assertTrue(ScoutStore.enabled(db,fixture.owner));assertEquals(java.util.List.of(fixture.owner),ScoutStore.recipients(db,2));assertEquals(java.util.List.of(fixture.attacker),ScoutStore.recipients(db,1));
            assertFalse(ScoutStore.toggle(db,fixture.owner));db.commit();ScoutStore.install(db);assertFalse(ScoutStore.enabled(db,fixture.owner));assertTrue(ScoutStore.recipients(db,2).isEmpty());assertTrue(ScoutStore.toggle(db,fixture.owner));db.rollback();assertFalse(ScoutStore.enabled(db,fixture.owner));
            assertTrue(ScoutStore.toggle(db,fixture.owner));db.commit();assertEquals(java.util.List.of(fixture.owner),ScoutStore.recipients(db,2));
        }
    }
    @Test void sourceCadenceIs120PulsesIndependentPerTowerAndZeroStillReports() {
        var clock=new ScoutClock(120);for(int i=0;i<119;i++)assertFalse(clock.ready(1));assertFalse(clock.ready(2));assertTrue(clock.ready(1));assertFalse(clock.ready(1));assertTrue(new ScoutClock(0).ready(3));assertFalse(new ScoutClock(120).ready(1));
    }
}
