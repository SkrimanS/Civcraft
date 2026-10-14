package com.avrgaming.civcraft.modern.war;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WarCampItemStoreTest {
    final WarCampStoreTest f=new WarCampStoreTest();
    @Test void worldPreparationWaitsForSavedPlayerInventoryAndFinishIsIdempotent()throws Exception {
        try(var db=f.database()) {
            var camp=f.prepare(db,"item",0);var player=f.f.owner.toString();byte[] after={1,2,3};
            var receipt=WarCampItemStore.prepare(db,"item",camp.id(),player,after,150);db.commit();
            assertNull(WarCampStore.nextPreparation(db));assertArrayEquals(after,WarCampItemStore.pending(db,player).inventory());
            assertEquals(camp.id(),WarCampItemStore.prepare(db,"item",camp.id(),player,new byte[]{9},151).camp());
            WarCampItemStore.finish(db,receipt);db.commit();WarCampItemStore.finish(db,receipt);db.commit();
            assertNull(WarCampItemStore.pending(db,player));assertEquals(camp.id(),WarCampStore.nextPreparation(db));
            assertArrayEquals(after,WarCampItemStore.checkpoint(db,player).inventory());
        }
    }
    @Test void receiptAndCampRollBackTogetherAndOnlyOnePendingReceiptIsAllowed()throws Exception {
        try(var db=f.database()) {
            var player=f.f.owner.toString();var camp=f.prepare(db,"rollback",0);
            WarCampItemStore.prepare(db,"rollback",camp.id(),player,new byte[]{1},150);db.rollback();
            assertNull(WarCampItemStore.pending(db,player));assertNull(WarCampStore.nextPreparation(db));
            var first=f.prepare(db,"first",0);WarCampItemStore.prepare(db,"first",first.id(),player,new byte[]{2},150);db.commit();
            var second=f.prepare(db,"second",1);
            assertThrows(java.sql.SQLException.class,()->WarCampItemStore.prepare(db,"second",second.id(),player,new byte[]{3},150));db.rollback();
            assertEquals(first.id(),WarCampItemStore.pending(db,player).camp());
            assertThrows(java.sql.SQLException.class,()->WarCampStore.camp(db,second.id()));
        }
    }
    @Test void failedFinishRetainsPendingReceiptUntilInventoryCheckpointCanCommit()throws Exception {
        try(var db=f.database()) {
            var camp=f.prepare(db,"finish",0);var player=f.f.owner.toString();
            var receipt=WarCampItemStore.prepare(db,"finish",camp.id(),player,new byte[]{4},150);db.commit();
            WarCampItemStore.finish(db,receipt);db.rollback();assertNotNull(WarCampItemStore.pending(db,player));
            assertNull(WarCampItemStore.checkpoint(db,player));assertNull(WarCampStore.nextPreparation(db));
            WarCampItemStore.finish(db,receipt);db.commit();assertEquals("finish",WarCampItemStore.checkpoint(db,player).id());
        }
    }
}
