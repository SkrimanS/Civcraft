package com.avrgaming.civcraft.modern.diplomacy;
import java.sql.*;
import java.util.UUID;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class DiplomacyStoreTest {
    final UUID a=new UUID(0,1),b=new UUID(0,2),member=new UUID(0,3);
    void schema(Connection db)throws Exception {
        try(var s=db.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("CREATE TABLE civilizations(id INTEGER PRIMARY KEY,leader_uuid TEXT)");
            s.execute("CREATE TABLE residents(uuid TEXT PRIMARY KEY,civ_id INTEGER)");
            s.execute("INSERT INTO civilizations VALUES(1,'"+a+"'),(2,'"+b+"')");
            s.execute("INSERT INTO residents VALUES('"+a+"',1),('"+b+"',2),('"+member+"',1)");
        }
        DiplomacyStore.install(db);db.setAutoCommit(false);db.commit();
    }
    Connection database()throws Exception {var db=DriverManager.getConnection("jdbc:sqlite::memory:");schema(db);return db;}
    @Test void defaultsSelfAllianceAndAcceptedPactAreSymmetricAndRespondCannotRepeat()throws Exception {
        try(var db=database()) {
            assertEquals(DiplomacyStore.Status.ALLY,DiplomacyStore.status(db,1,1));assertEquals(DiplomacyStore.Status.NEUTRAL,DiplomacyStore.status(db,1,2));
            var offer=DiplomacyStore.request(db,1,2,a,DiplomacyStore.Status.ALLY,1000);db.commit();assertEquals(31000,offer.expires());
            assertEquals(DiplomacyStore.Status.NEUTRAL,DiplomacyStore.status(db,1,2));
            DiplomacyStore.respond(db,2,b,true,2000);db.commit();
            assertEquals(DiplomacyStore.Status.ALLY,DiplomacyStore.status(db,1,2));assertEquals(DiplomacyStore.Status.ALLY,DiplomacyStore.status(db,2,1));
            assertThrows(IllegalArgumentException.class,()->DiplomacyStore.respond(db,2,b,true,3000));db.rollback();
            try(var r=db.createStatement().executeQuery("SELECT COUNT(*) FROM diplomacy_audit")){assertTrue(r.next());assertEquals(1,r.getInt(1));}
        }
    }
    @Test void onlyLeadershipOrAnActualMemberAdvisorMayChangeRelations()throws Exception {
        try(var db=database()) {
            assertThrows(IllegalArgumentException.class,()->DiplomacyStore.request(db,1,2,member,DiplomacyStore.Status.ALLY,1000));db.rollback();
            assertThrows(IllegalArgumentException.class,()->DiplomacyStore.advisor(db,1,a,b,true));db.rollback();
            DiplomacyStore.advisor(db,1,a,member,true);db.commit();DiplomacyStore.authority(db,1,member);
            db.createStatement().execute("UPDATE residents SET civ_id=2 WHERE uuid='"+member+"'");db.commit();
            db.createStatement().execute("UPDATE residents SET civ_id=1 WHERE uuid='"+member+"'");db.commit();
            assertThrows(IllegalArgumentException.class,()->DiplomacyStore.authority(db,1,member));
            assertThrows(IllegalArgumentException.class,()->DiplomacyStore.request(db,1,1,a,DiplomacyStore.Status.ALLY,1000));
        }
    }
    @Test void declineAndExpirationLeaveRelationshipUnchangedAndRecipientHasOneQuestion()throws Exception {
        try(var db=database()) {
            DiplomacyStore.request(db,1,2,a,DiplomacyStore.Status.PEACE,1000);db.commit();
            assertThrows(IllegalArgumentException.class,()->DiplomacyStore.request(db,1,2,a,DiplomacyStore.Status.ALLY,1100));db.rollback();
            DiplomacyStore.respond(db,2,b,false,2000);db.commit();assertEquals(DiplomacyStore.Status.NEUTRAL,DiplomacyStore.status(db,1,2));
            DiplomacyStore.request(db,1,2,a,DiplomacyStore.Status.PEACE,3000);db.commit();
            assertThrows(IllegalArgumentException.class,()->DiplomacyStore.respond(db,2,b,true,33000));db.rollback();
            DiplomacyStore.expire(db,33000);db.commit();assertNull(DiplomacyStore.incoming(db,2,33000));
        }
    }
    @Test void hostileDeclarationCancelsOffersAndCannotEraseWar()throws Exception {
        try(var db=database()) {
            DiplomacyStore.request(db,1,2,a,DiplomacyStore.Status.ALLY,1000);db.commit();
            var relation=DiplomacyStore.hostile(db,1,2,a,2000);db.commit();assertEquals(1L,relation.aggressor());
            assertEquals(DiplomacyStore.Status.HOSTILE,DiplomacyStore.status(db,2,1));assertNull(DiplomacyStore.incoming(db,2,2000));
            db.createStatement().execute("UPDATE diplomacy_relations SET status='WAR'");db.commit();
            assertThrows(IllegalArgumentException.class,()->DiplomacyStore.hostile(db,1,2,a,3000));db.rollback();assertEquals(DiplomacyStore.Status.WAR,DiplomacyStore.status(db,1,2));
        }
    }
    @Test void rollbackAndStaleOppositeRequestCannotOverwriteAnotherAgreement()throws Exception {
        try(var db=database()) {
            DiplomacyStore.request(db,1,2,a,DiplomacyStore.Status.ALLY,1000);db.rollback();assertNull(DiplomacyStore.incoming(db,2,1000));
            DiplomacyStore.request(db,1,2,a,DiplomacyStore.Status.ALLY,1000);DiplomacyStore.request(db,2,1,b,DiplomacyStore.Status.PEACE,1000);db.commit();
            DiplomacyStore.respond(db,2,b,true,2000);db.commit();
            assertThrows(IllegalArgumentException.class,()->DiplomacyStore.respond(db,1,a,true,3000));db.rollback();assertEquals(DiplomacyStore.Status.ALLY,DiplomacyStore.status(db,1,2));
        }
    }
    @Test void pendingQuestionAndAcceptedPactSurviveDatabaseReopen(@TempDir Path directory)throws Exception {
        String url="jdbc:sqlite:"+directory.resolve("dip.db");
        try(var db=DriverManager.getConnection(url)){schema(db);DiplomacyStore.request(db,1,2,a,DiplomacyStore.Status.ALLY,1000);db.commit();}
        try(var db=DriverManager.getConnection(url)){db.setAutoCommit(false);assertNotNull(DiplomacyStore.incoming(db,2,2000));DiplomacyStore.respond(db,2,b,true,2000);db.commit();}
        try(var db=DriverManager.getConnection(url)){assertEquals(DiplomacyStore.Status.ALLY,DiplomacyStore.status(db,2,1));}
    }
}
