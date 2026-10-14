package com.avrgaming.civcraft.modern.war;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WarCampDropsStoreTest {
    final WarCampStoreTest f=new WarCampStoreTest();
    @Test void inventoryEvacuationIsImmutableOnReplayAndCommittedOnlyAfterWorldSave()throws Exception {
        try(var db=f.database()) {
            WarCampDropsStore.install(db);var camp=f.prepare(db,"one",0);WarCampStore.scheduleRestoration(db,200);db.commit();byte[] original={1,2,3};
            var drop=WarCampDropsStore.prepare(db,camp.id(),"world",0,80,0,original);db.commit();var replay=WarCampDropsStore.prepare(db,camp.id(),"world",0,80,0,new byte[]{9});assertEquals(drop.id(),replay.id());assertArrayEquals(original,replay.items());assertEquals("PREPARED",replay.state());
            WarCampDropsStore.finish(db,camp.id());db.rollback();assertEquals("PREPARED",WarCampDropsStore.list(db,camp.id()).getFirst().state());WarCampDropsStore.finish(db,camp.id());db.commit();assertEquals("DONE",WarCampDropsStore.list(db,camp.id()).getFirst().state());
            assertEquals(java.util.List.of(camp.id()),WarCampDropsStore.unreleasedCamps(db));WarCampDropsStore.release(db,camp.id());db.rollback();assertFalse(WarCampDropsStore.list(db,camp.id()).getFirst().released());WarCampDropsStore.release(db,camp.id());db.commit();assertTrue(WarCampDropsStore.list(db,camp.id()).getFirst().released());assertTrue(WarCampDropsStore.unreleasedCamps(db).isEmpty());
        }
    }
    @Test void evictionOfAnActiveCampIsRejectedAndPreparationRollbackLeavesNoDrop()throws Exception {
        try(var db=f.database()) {
            WarCampDropsStore.install(db);var camp=f.prepare(db,"one",0);WarCampStore.activate(db,camp.id(),151);db.commit();assertThrows(IllegalArgumentException.class,()->WarCampDropsStore.prepare(db,camp.id(),"world",0,80,0,new byte[0]));db.rollback();
            WarCampStore.scheduleRestoration(db,200);db.commit();WarCampDropsStore.prepare(db,camp.id(),"world",0,80,0,new byte[]{7});db.rollback();assertTrue(WarCampDropsStore.list(db,camp.id()).isEmpty());
        }
    }
}
