package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import com.avrgaming.civcraft.modern.diplomacy.DiplomacyStore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CapitulationStoreTest {
    final OccupationActionsStoreTest f=new OccupationActionsStoreTest();
    Connection database()throws Exception {var db=f.database();DiplomacyStore.install(db);db.commit();return db;}
    @Test void wholeCapitulationRequiresBothSidesAndKeepsTownsButDeletesDormantMotherland()throws Exception {
        try(var db=database()) {
            var offer=CapitulationStore.offer(db,"Capital",f.motherLeader);assertTrue(offer.whole());assertEquals(0,f.value(db,"SELECT COUNT(*) FROM capitulation_requests"));
            CapitulationStore.request(db,"Capital",f.motherLeader,1000);db.commit();assertTrue(ConquestStore.conquered(db,2));
            assertThrows(IllegalArgumentException.class,()->CapitulationStore.respond(db,1,f.member,true,2000));db.rollback();
            CapitulationStore.respond(db,1,f.leader,true,2000);db.commit();assertEquals(0,f.value(db,"SELECT COUNT(*) FROM civilizations WHERE id=2"));
            assertEquals(1,f.value(db,"SELECT civ_id FROM towns WHERE id=2"));assertEquals(3,f.value(db,"SELECT civ_id FROM towns WHERE id=4"));assertEquals(0,f.value(db,"SELECT COUNT(*) FROM town_conquests WHERE mother_civ IS NOT NULL"));
            assertEquals(1,f.value(db,"SELECT COUNT(*) FROM capitulation_requests WHERE state='ACCEPTED'"));assertEquals(1,f.value(db,"SELECT COUNT(*) FROM occupation_actions"));
            assertEquals(3,f.value(db,"SELECT towns FROM occupation_actions"));
            assertThrows(IllegalArgumentException.class,()->CapitulationStore.respond(db,1,f.leader,true,2001));db.rollback();assertEquals(1,f.value(db,"SELECT COUNT(*) FROM occupation_actions"));
        }
    }
    @Test void singleTownCapitulationDoesNotRemoveMotherlandOrOtherCapturedCities()throws Exception {
        try(var db=database()) {
            assertThrows(IllegalArgumentException.class,()->CapitulationStore.offer(db,"Colony",f.member));db.rollback();
            CapitulationStore.request(db,"Colony",f.motherLeader,1000);CapitulationStore.respond(db,1,f.leader,true,2000);db.commit();
            assertEquals(1,f.value(db,"SELECT COUNT(*) FROM civilizations WHERE id=2"));assertTrue(ConquestStore.conquered(db,2));assertEquals(2,f.value(db,"SELECT COUNT(*) FROM town_conquests WHERE mother_civ IS NOT NULL"));
            assertEquals(1,f.value(db,"SELECT civ_id FROM towns WHERE id=3"));
        }
    }
    @Test void declineExpiryAndChangedCaptureCannotDeleteAnyCivilization()throws Exception {
        try(var db=database()) {
            CapitulationStore.request(db,"Capital",f.motherLeader,1000);CapitulationStore.respond(db,1,f.leader,false,2000);db.commit();assertTrue(ConquestStore.conquered(db,2));
            CapitulationStore.request(db,"Capital",f.motherLeader,3000);db.commit();assertThrows(IllegalArgumentException.class,()->CapitulationStore.respond(db,1,f.leader,true,33000));db.rollback();
            CapitulationStore.expire(db,33000);db.commit();CapitulationStore.request(db,"Capital",f.motherLeader,34000);db.commit();db.createStatement().execute("UPDATE town_conquests SET changed_at=1 WHERE town_id=2");db.commit();
            assertThrows(IllegalArgumentException.class,()->CapitulationStore.respond(db,1,f.leader,true,35000));db.rollback();assertTrue(ConquestStore.conquered(db,2));assertEquals(0,f.value(db,"SELECT COUNT(*) FROM occupation_actions"));
        }
    }
    @Test void treatyAndCapitulationShareOneIncomingQuestionSlot()throws Exception {
        try(var db=database()) {
            CapitulationStore.request(db,"Capital",f.motherLeader,1000);db.commit();assertThrows(IllegalArgumentException.class,()->DiplomacyStore.request(db,3,1,f.thirdLeader,DiplomacyStore.Status.ALLY,2000));db.rollback();
            CapitulationStore.respond(db,1,f.leader,false,2000);db.commit();DiplomacyStore.request(db,3,1,f.thirdLeader,DiplomacyStore.Status.ALLY,3000);db.commit();assertThrows(IllegalArgumentException.class,()->CapitulationStore.request(db,"Capital",f.motherLeader,4000));db.rollback();
        }
    }
    @Test void formerLeaderWithoutCurrentCivilizationCanProposeAndRollbackKeepsEverything()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("UPDATE residents SET town_id=NULL,civ_id=NULL WHERE uuid='"+f.motherLeader+"'");db.commit();CapitulationStore.request(db,"Capital",f.motherLeader,1000);db.commit();
            CapitulationStore.respond(db,1,f.leader,true,2000);db.rollback();assertTrue(ConquestStore.conquered(db,2));assertEquals(3,f.value(db,"SELECT COUNT(*) FROM town_conquests WHERE mother_civ IS NOT NULL"));assertNotNull(CapitulationStore.incoming(db,1,2001));
        }
    }
    @Test void deletingMotherlandDoesNotLeaveDanglingResidentMembership()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("UPDATE residents SET town_id=NULL,civ_id=2 WHERE uuid='"+f.motherLeader+"'");db.commit();
            CapitulationStore.request(db,"Capital",f.motherLeader,1000);CapitulationStore.respond(db,1,f.leader,true,2000);db.commit();
            try(var r=db.createStatement().executeQuery("SELECT civ_id FROM residents WHERE uuid='"+f.motherLeader+"'")){r.next();assertNull(r.getObject(1));}
        }
    }
}
