package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BuildingDamageStoreTest {
    final UUID attacker=new UUID(0,1),owner=new UUID(0,2),homeless=new UUID(0,3);
    Connection database()throws Exception {
        var db=DriverManager.getConnection("jdbc:sqlite::memory:");
        try(var s=db.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");s.execute("CREATE TABLE civilizations(id INTEGER PRIMARY KEY)");s.execute("INSERT INTO civilizations VALUES(1),(2)");
            s.execute("CREATE TABLE towns(id INTEGER PRIMARY KEY,civ_id INTEGER)");s.execute("INSERT INTO towns VALUES(1,1),(2,2)");
            s.execute("CREATE TABLE residents(uuid TEXT PRIMARY KEY,town_id INTEGER,civ_id INTEGER)");s.execute("INSERT INTO residents VALUES('"+attacker+"',1,1),('"+owner+"',2,2),('"+homeless+"',NULL,1)");
            s.execute("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY,town_id INTEGER,structure_id TEXT,status TEXT,max_hitpoints INTEGER,display_name TEXT)");s.execute("INSERT INTO structure_builds VALUES(1,2,'s_grocer','COMPLETE',5,'Бакалея')");
            s.execute("CREATE TABLE protected_blocks(world TEXT,x INTEGER,y INTEGER,z INTEGER,build_id INTEGER)");s.execute("INSERT INTO protected_blocks VALUES('world',1,80,1,1)");
            s.execute("CREATE TABLE diplomacy_relations(first_civ INTEGER,second_civ INTEGER,status TEXT,aggressor INTEGER,changed_at INTEGER)");s.execute("INSERT INTO diplomacy_relations VALUES(1,2,'WAR',1,0)");
        }
        WarClockStore.install(db);db.createStatement().execute("INSERT INTO war_sessions VALUES(100,200,'ACTIVE')");BuildingDamageStore.install(db);db.setAutoCommit(false);db.commit();return db;
    }
    BuildingDamageStore.Health hit(Connection db,String id,UUID actor,int amount,long now)throws Exception{return BuildingDamageStore.hit(db,id,actor,"world",1,80,1,amount,now);}
    @Test void oneHitDecrementsHealthAndReplayNeverRepeatsTheDamage()throws Exception {
        try(var db=database()) {
            assertEquals(4,hit(db,"first",attacker,1,150).hitpoints());db.commit();assertEquals(4,hit(db,"first",attacker,1,150).hitpoints());db.commit();
            try(var r=db.createStatement().executeQuery("SELECT COUNT(*) FROM building_damage_hits")){assertTrue(r.next());assertEquals(1,r.getInt(1));}
        }
    }
    @Test void actorMustHaveAnUndefeatedTownAndAnEnemyWarRelation()throws Exception {
        try(var db=database()) {
            assertThrows(IllegalArgumentException.class,()->hit(db,"own",owner,1,150));db.rollback();
            assertThrows(IllegalArgumentException.class,()->hit(db,"homeless",homeless,1,150));db.rollback();
            db.createStatement().execute("UPDATE diplomacy_relations SET status='ALLY'");db.commit();assertThrows(IllegalArgumentException.class,()->hit(db,"ally",attacker,1,150));db.rollback();
            db.createStatement().execute("UPDATE diplomacy_relations SET status='WAR'");db.createStatement().execute("INSERT INTO war_defeated_towns VALUES(100,1,2)");db.commit();assertThrows(IllegalArgumentException.class,()->hit(db,"defeated",attacker,1,150));db.rollback();
            assertEquals(5,BuildingDamageStore.health(db,1).hitpoints());
        }
    }
    @Test void battleBoundariesConstructionAndSpecialStructuresRejectOrdinaryDamage()throws Exception {
        try(var db=database()) {
            assertThrows(IllegalArgumentException.class,()->hit(db,"early",attacker,1,99));assertThrows(IllegalArgumentException.class,()->hit(db,"late",attacker,1,200));db.rollback();
            for(String type:new String[]{"s_townhall","s_capitol","w_pyramid"}) {
                db.createStatement().execute("UPDATE structure_builds SET structure_id='"+type+"'");db.commit();assertThrows(IllegalArgumentException.class,()->hit(db,type,attacker,1,150));db.rollback();
            }
            db.createStatement().execute("UPDATE structure_builds SET structure_id='s_grocer',status='BUILDING'");db.commit();assertThrows(IllegalArgumentException.class,()->hit(db,"building",attacker,1,150));db.rollback();
        }
    }
    @Test void zeroHealthCreatesDurableDestructionIntentAndCannotGoNegative()throws Exception {
        try(var db=database()) {
            var health=hit(db,"lethal",attacker,100,150);db.commit();assertEquals(0,health.hitpoints());assertEquals("DESTROY_PENDING",health.phase());
            try(var r=db.createStatement().executeQuery("SELECT status FROM structure_builds")){assertTrue(r.next());assertEquals("WAR_DESTROY_PENDING",r.getString(1));}
            assertThrows(IllegalArgumentException.class,()->hit(db,"again",attacker,1,151));db.rollback();assertEquals(0,BuildingDamageStore.health(db,1).hitpoints());
        }
    }
    @Test void rollbackUndoesHealthIntentAndAuditTogether()throws Exception {
        try(var db=database()) {
            hit(db,"lethal",attacker,5,150);db.rollback();assertEquals(5,BuildingDamageStore.health(db,1).hitpoints());
            try(var r=db.createStatement().executeQuery("SELECT status FROM structure_builds")){assertTrue(r.next());assertEquals("COMPLETE",r.getString(1));}
            assertEquals(4,hit(db,"lethal",attacker,1,150).hitpoints());db.commit();
        }
    }
    @Test void healthPersistsAcrossBattlePeriodsInsteadOfReceivingFreeHealing()throws Exception {
        try(var db=database()) {
            hit(db,"first",attacker,1,150);db.commit();db.createStatement().execute("UPDATE war_sessions SET state='CLOSED'");db.createStatement().execute("INSERT INTO war_sessions VALUES(300,400,'ACTIVE')");db.commit();
            assertEquals(4,BuildingDamageStore.health(db,1).hitpoints());assertEquals(3,hit(db,"second",attacker,1,350).hitpoints());db.commit();
        }
    }
}
