package com.avrgaming.civcraft.modern.wonder;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HangingGardensHealingTest {
    final UUID member=UUID.randomUUID(),visitor=UUID.randomUUID(),homeless=UUID.randomUUID();
    Connection database()throws Exception {var db=DriverManager.getConnection("jdbc:sqlite::memory:");try(var s=db.createStatement()){
        s.execute("CREATE TABLE residents(uuid TEXT,town_id INTEGER,civ_id INTEGER)");s.execute("INSERT INTO residents VALUES('"+member+"',2,1),('"+visitor+"',3,2),('"+homeless+"',NULL,1)");
        s.execute("CREATE TABLE towns(id INTEGER,civ_id INTEGER)");s.execute("INSERT INTO towns VALUES(1,1),(2,1),(3,2)");
        s.execute("CREATE TABLE town_claims(world TEXT,chunk_x INTEGER,chunk_z INTEGER,town_id INTEGER)");s.execute("INSERT INTO town_claims VALUES('world',-1,-2,1),('world',0,0,2),('world',1,1,3)");
        s.execute("CREATE TABLE structure_builds(town_id INTEGER,structure_id TEXT,status TEXT)");s.execute("INSERT INTO structure_builds VALUES(1,'w_hanginggardens','COMPLETE')");}return db;}
    @Test void onlyOwnCivilizationMembersOnHostClaimsReceiveHealing()throws Exception {try(var db=database()){assertTrue(HangingGardensHealing.eligible(db,member,"world",-1,-2));assertFalse(HangingGardensHealing.eligible(db,visitor,"world",-1,-2));assertFalse(HangingGardensHealing.eligible(db,homeless,"world",-1,-2));assertFalse(HangingGardensHealing.eligible(db,member,"world",0,0));assertFalse(HangingGardensHealing.eligible(db,member,"other",-1,-2));assertFalse(HangingGardensHealing.eligible(db,member,"world",9,9));}}
    @Test void originalUnderConstructionHealingStopsWhenTheWonderIsRemoved()throws Exception {try(var db=database()){for(var state:List.of("QUEUED","BUILDING","COMPLETE")){db.createStatement().execute("UPDATE structure_builds SET status='"+state+"'");assertTrue(HangingGardensHealing.eligible(db,member,"world",-1,-2));}for(var state:List.of("WAR_DESTROYED","DEMOLISHED","CANCELLED","ROLLBACK_PENDING")){db.createStatement().execute("UPDATE structure_builds SET status='"+state+"'");assertFalse(HangingGardensHealing.eligible(db,member,"world",-1,-2));}}}
    @Test void ownershipTransferImmediatelyChangesWhoCanHeal()throws Exception {try(var db=database()){db.createStatement().execute("UPDATE towns SET civ_id=2 WHERE id=1");assertFalse(HangingGardensHealing.eligible(db,member,"world",-1,-2));assertTrue(HangingGardensHealing.eligible(db,visitor,"world",-1,-2));}}
    @Test void oneHealthPointPerUpdateClampsAtTwentyAndDoesNotResurrect(){assertEquals(11,HangingGardensHealing.nextHealth(10,20));assertEquals(20,HangingGardensHealing.nextHealth(19.5,20));assertEquals(20,HangingGardensHealing.nextHealth(20,40));assertEquals(25,HangingGardensHealing.nextHealth(25,40));assertEquals(10,HangingGardensHealing.nextHealth(9.5,10));assertEquals(0,HangingGardensHealing.nextHealth(0,20));}
}
