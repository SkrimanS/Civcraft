package com.avrgaming.civcraft.modern.war;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ControlPointBonusStoreTest {
    final ControlPointStoreTest fixture=new ControlPointStoreTest();
    ControlPointStore.Point point(java.sql.Connection db)throws Exception{return ControlPointStore.point(db,"world",1,81,1);}
    @Test void completedWonderIsLocalNonstackingAndRestartsDoNotAddAgain()throws Exception {
        try(var db=fixture.database()) {
            db.createStatement().execute("UPDATE war_sessions SET state='CLOSED'");
            db.createStatement().execute("INSERT INTO structure_builds VALUES(2,2,'w_chichen_itza','BUILDING',1,'Itza'),(3,1,'w_chichen_itza','COMPLETE',1,'Foreign Itza')");
            ControlPointBonusStore.reconcile(db,10,150);assertEquals(20,point(db).maximum());
            db.createStatement().execute("UPDATE structure_builds SET status='COMPLETE' WHERE id=2");
            ControlPointBonusStore.reconcile(db,10,150);assertEquals(30,point(db).maximum());
            db.createStatement().execute("INSERT INTO structure_builds VALUES(4,2,'w_chichen_itza','COMPLETE',1,'Duplicate')");
            db.commit();ControlPointStore.install(db);ControlPointStore.register(db,fixture.first);ControlPointBonusStore.reconcile(db,10,150);assertEquals(30,point(db).maximum());
            db.createStatement().execute("UPDATE structure_builds SET status='DEMOLISHED' WHERE id IN (2,4)");ControlPointBonusStore.reconcile(db,10,150);assertEquals(20,point(db).maximum());
        }
    }
    @Test void capacityChangeNeverHealsOrKillsBattlePointsAndNextWarUsesNewMaximum()throws Exception {
        try(var db=fixture.database()) {
            ControlPointStore.hit(db,"damage",fixture.f.attacker,fixture.first,3,150);
            db.createStatement().execute("INSERT INTO structure_builds VALUES(2,2,'w_chichen_itza','COMPLETE',1,'Itza')");
            ControlPointBonusStore.reconcile(db,10,150);assertEquals(30,point(db).maximum());assertEquals(17,ControlPointStore.health(db,point(db),100));assertEquals(20,ControlPointStore.health(db,ControlPointStore.point(db,"world",2,81,1),100));
            db.createStatement().execute("UPDATE war_sessions SET state='CLOSED'");db.createStatement().execute("INSERT INTO war_sessions VALUES(300,400,'ACTIVE')");
            ControlPointBonusStore.reconcile(db,10,350);assertEquals(30,ControlPointStore.health(db,point(db),300));
            db.createStatement().execute("UPDATE structure_builds SET town_id=1 WHERE id=2");ControlPointBonusStore.reconcile(db,10,350);assertEquals(20,point(db).maximum());assertEquals(30,ControlPointStore.health(db,point(db),300));
            assertEquals(29,ControlPointStore.hit(db,"after-transfer",fixture.f.attacker,point(db),1,350).health());
        }
    }
}
