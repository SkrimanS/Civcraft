package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WarPvpStoreTest {
    final BuildingDamageStoreTest f=new BuildingDamageStoreTest();
    Connection database()throws Exception {
        var db=f.database();try(var s=db.createStatement()) {
            s.execute("CREATE TABLE town_claims(world TEXT,chunk_x INTEGER,chunk_z INTEGER,town_id INTEGER)");s.execute("INSERT INTO town_claims VALUES('world',1,1,2)");
            s.execute("ALTER TABLE towns ADD COLUMN mayor_uuid TEXT");s.execute("UPDATE towns SET mayor_uuid='"+f.owner+"' WHERE id=2");s.execute("ALTER TABLE civilizations ADD COLUMN leader_uuid TEXT");s.execute("UPDATE civilizations SET leader_uuid='"+f.owner+"' WHERE id=2");
        }OutlawStore.install(db);db.commit();return db;
    }
    @Test void wildernessAndOutlawExceptionsAllowPvpWithoutANationWar() {
        assertEquals(WarPvpStore.Decision.ALLOWED,WarPvpStore.decide(false,false,false,false,false,false,false));
        assertEquals(WarPvpStore.Decision.ALLOWED,WarPvpStore.decide(true,true,false,false,false,false,false));
        assertEquals(WarPvpStore.Decision.NOT_AT_WAR,WarPvpStore.decide(true,false,false,true,true,false,true));
    }
    @Test void enemiesCanFightInClaimsOutsideBattleHours()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("UPDATE war_sessions SET state='CLOSED'");db.commit();assertEquals(WarPvpStore.Decision.ALLOWED,WarPvpStore.check(db,f.attacker,f.owner,"world",1,1,150));
            db.createStatement().execute("UPDATE diplomacy_relations SET status='PEACE'");db.commit();assertEquals(WarPvpStore.Decision.NOT_AT_WAR,WarPvpStore.check(db,f.attacker,f.owner,"world",1,1,150));
        }
    }
    @Test void neutralVisitorsCanBeAttackedInWarZonesButCannotAttackParticipants()throws Exception {
        try(var db=database()) {
            assertEquals(WarPvpStore.Decision.ALLOWED,WarPvpStore.check(db,f.owner,f.homeless,"world",1,1,150));
            assertEquals(WarPvpStore.Decision.NEUTRAL_IN_WARZONE,WarPvpStore.check(db,f.homeless,f.owner,"world",1,1,150));
            db.createStatement().execute("UPDATE war_sessions SET state='CLOSED'");db.commit();assertEquals(WarPvpStore.Decision.NON_PVP_ZONE,WarPvpStore.check(db,f.owner,f.homeless,"world",1,1,150));
        }
    }
    @Test void outlawDeclarationIsDelayedAuthorizedAndLimitedToTheDeclaringTown()throws Exception {
        try(var db=database()) {
            assertThrows(IllegalArgumentException.class,()->OutlawStore.add(db,2,f.homeless,f.attacker,1000));db.rollback();assertThrows(IllegalArgumentException.class,()->OutlawStore.add(db,2,f.owner,f.owner,1000));db.rollback();
            OutlawStore.add(db,2,f.owner,f.attacker,1000);db.commit();assertFalse(OutlawStore.active(db,2,f.attacker,1999));assertTrue(OutlawStore.active(db,2,f.attacker,2000));assertFalse(OutlawStore.active(db,1,f.attacker,2000));
            db.createStatement().execute("UPDATE diplomacy_relations SET status='NEUTRAL'");db.commit();assertEquals(WarPvpStore.Decision.ALLOWED,WarPvpStore.check(db,f.attacker,f.owner,"world",1,1,2000));
            OutlawStore.remove(db,2,f.owner,f.attacker);db.commit();assertFalse(OutlawStore.active(db,2,f.attacker,2001));
        }
    }
    @Test void ownershipChangeRemovesOutlawsWhoBelongToTheNewCivilization()throws Exception {
        try(var db=database()) {
            OutlawStore.add(db,2,f.owner,f.attacker,1000);OutlawStore.add(db,2,f.owner,f.homeless,1000);db.commit();OutlawStore.removeNewCivilizationMembers(db,2,1);db.commit();assertFalse(OutlawStore.active(db,2,f.attacker,2000));assertTrue(OutlawStore.active(db,2,f.homeless,2000));
            assertEquals(1,OutlawStore.group(db,2,f.owner,java.util.List.of(f.attacker),true,3000));db.rollback();assertFalse(OutlawStore.active(db,2,f.attacker,4000));
        }
    }
}
