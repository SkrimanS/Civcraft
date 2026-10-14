package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ControlPointStoreTest {
    final BuildingDamageStoreTest f=new BuildingDamageStoreTest();
    final ControlPointStore.Point first=new ControlPointStore.Point(1,"world",1,81,1,20,"OAK_FENCE"),second=new ControlPointStore.Point(1,"world",2,81,1,20,"OAK_FENCE");
    Connection database()throws Exception {
        var db=f.database();db.createStatement().execute("ALTER TABLE protected_blocks ADD COLUMN material TEXT");db.createStatement().execute("CREATE UNIQUE INDEX protected_coordinate ON protected_blocks(world,x,y,z)");db.createStatement().execute("UPDATE structure_builds SET structure_id='s_townhall'");ControlPointStore.install(db);ControlPointStore.register(db,first);ControlPointStore.register(db,second);db.commit();return db;
    }
    @Test void hitsAndDestroyedPointsPersistAndReplayDoesNotDamageAgain()throws Exception {
        try(var db=database()) {
            assertEquals(19,ControlPointStore.hit(db,"one",f.attacker,first,1,150).health());db.commit();assertEquals(19,ControlPointStore.hit(db,"one",f.attacker,first,1,150).health());
            assertFalse(ControlPointStore.hit(db,"lethal",f.attacker,first,100,150).townDefeated());db.commit();assertEquals(0,ControlPointStore.health(db,first,100));
            assertThrows(IllegalArgumentException.class,()->ControlPointStore.hit(db,"again",f.attacker,first,1,150));db.rollback();
            assertTrue(ControlPointStore.hit(db,"last",f.attacker,second,20,150).townDefeated());db.commit();
            try(var r=db.createStatement().executeQuery("SELECT conqueror FROM war_defeated_towns WHERE town_id=2")){assertTrue(r.next());assertEquals(1,r.getLong(1));}
        }
    }
    @Test void ownerHomelessNeutralAndBattleBoundariesRejectDamage()throws Exception {
        try(var db=database()) {
            assertThrows(IllegalArgumentException.class,()->ControlPointStore.hit(db,"own",f.owner,first,1,150));
            assertThrows(IllegalArgumentException.class,()->ControlPointStore.hit(db,"none",f.homeless,first,1,150));
            assertThrows(IllegalArgumentException.class,()->ControlPointStore.hit(db,"early",f.attacker,first,1,99));
            assertThrows(IllegalArgumentException.class,()->ControlPointStore.hit(db,"late",f.attacker,first,1,200));db.rollback();
            db.createStatement().execute("UPDATE diplomacy_relations SET status='NEUTRAL'");db.commit();assertThrows(IllegalArgumentException.class,()->ControlPointStore.hit(db,"neutral",f.attacker,first,1,150));db.rollback();assertEquals(20,ControlPointStore.health(db,first,100));
        }
    }
    @Test void capitolDefeatMarksAllCitiesAndPreventsFurtherAttackByTheLoser()throws Exception {
        try(var db=database()) {
            db.createStatement().execute("UPDATE structure_builds SET structure_id='s_capitol'");db.createStatement().execute("INSERT INTO towns VALUES(3,2)");db.commit();
            ControlPointStore.hit(db,"first",f.attacker,first,20,150);var hit=ControlPointStore.hit(db,"last",f.attacker,second,20,150);db.commit();assertTrue(hit.civDefeated());
            try(var r=db.createStatement().executeQuery("SELECT COUNT(*) FROM war_defeated_towns WHERE conqueror=1")){r.next();assertEquals(2,r.getInt(1));}
            assertThrows(IllegalArgumentException.class,()->ControlPointStore.hit(db,"loser",f.owner,first,1,150));db.rollback();
        }
    }
    @Test void rollbackIsAtomicAndNextWarStartsWithFullPointHealth()throws Exception {
        try(var db=database()) {
            ControlPointStore.hit(db,"first",f.attacker,first,20,150);ControlPointStore.hit(db,"last",f.attacker,second,20,150);db.rollback();assertEquals(20,ControlPointStore.health(db,first,100));
            ControlPointStore.hit(db,"first",f.attacker,first,20,150);db.commit();
            db.createStatement().execute("UPDATE war_sessions SET state='CLOSED'");db.createStatement().execute("INSERT INTO war_sessions VALUES(300,400,'ACTIVE')");db.commit();
            assertEquals(19,ControlPointStore.hit(db,"next",f.attacker,first,1,350).health());db.commit();assertEquals(0,ControlPointStore.health(db,first,100));
        }
    }
    @Test void markerRegistrationNeverStealsAnotherBuildingsCoordinate()throws Exception {
        try(var db=database()) {
            var conflict=new ControlPointStore.Point(1,"world",3,81,1,20,"OAK_FENCE");db.createStatement().execute("INSERT INTO protected_blocks(world,x,y,z,build_id) VALUES('world',3,81,1,99)");db.commit();
            assertThrows(SQLException.class,()->ControlPointStore.register(db,conflict));db.rollback();assertNull(ControlPointStore.point(db,"world",3,81,1));
        }
    }
    @Test void capitolVictoryInheritsEarlierCapturesWithoutReactivatingDefeatedCities()throws Exception {
        try(var db=database()) {
            try(var s=db.createStatement()) {
                s.execute("UPDATE structure_builds SET structure_id='s_capitol'");s.execute("INSERT INTO civilizations VALUES(3)");s.execute("INSERT INTO towns VALUES(3,3),(4,1)");
                s.execute("INSERT INTO war_defeated_towns VALUES(100,3,2),(100,4,2)");s.execute("INSERT INTO war_inactive_towns VALUES(100,3),(100,4)");s.execute("INSERT INTO war_defeated_civs VALUES(100,3,2)");
            }db.commit();
            ControlPointStore.hit(db,"first",f.attacker,first,20,150);ControlPointStore.hit(db,"last",f.attacker,second,20,150);db.commit();
            try(var r=db.createStatement().executeQuery("SELECT conqueror FROM war_defeated_towns WHERE town_id=3")){r.next();assertEquals(1,r.getLong(1));}
            try(var r=db.createStatement().executeQuery("SELECT conqueror FROM war_defeated_civs WHERE civ_id=3")){r.next();assertEquals(1,r.getLong(1));}
            try(var r=db.createStatement().executeQuery("SELECT COUNT(*) FROM war_defeated_towns WHERE town_id=4")){r.next();assertEquals(0,r.getInt(1));}
            try(var r=db.createStatement().executeQuery("SELECT COUNT(*) FROM war_inactive_towns WHERE town_id=4")){r.next();assertEquals(1,r.getInt(1));}
        }
    }
}
