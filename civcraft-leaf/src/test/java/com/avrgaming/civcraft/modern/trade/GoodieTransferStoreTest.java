package com.avrgaming.civcraft.modern.trade;

import java.sql.*;
import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GoodieTransferStoreTest {
    @TempDir Path directory;
    final UUID player=UUID.fromString("11111111-1111-1111-1111-111111111111");
    final TradeGoodsCatalog catalog=new TradeGoodsCatalog(new File("src/main/resources/civcraft/data/goods.yml"));
    void schema(Connection db)throws SQLException {
        db.createStatement().execute("PRAGMA foreign_keys=ON");db.createStatement().execute("CREATE TABLE towns(id INTEGER PRIMARY KEY)");db.createStatement().execute("INSERT INTO towns VALUES(1),(2)");
        db.createStatement().execute("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY,town_id INTEGER,structure_id TEXT,status TEXT,max_hitpoints INTEGER)");
        db.createStatement().execute("INSERT INTO structure_builds VALUES(1,1,'ti_trade_outpost','COMPLETE',100),(2,1,'ti_trade_outpost','COMPLETE',100),(3,2,'s_townhall','COMPLETE',100)");
        db.createStatement().execute("CREATE TABLE building_combat_health(build_id INTEGER,hitpoints INTEGER)");
        TradeDepositStore.install(db);BonusGoodieStore.install(db);GoodieTransferStore.install(db);
        TradeDepositStore.register(db,catalog,"good_pelts","world",1,70,1);TradeDepositStore.register(db,catalog,"good_pelts","world",33,70,1);
        db.createStatement().execute("UPDATE trade_deposits SET build_id=CASE x WHEN 1 THEN 1 ELSE 2 END");
        GoodieFrameStore.register(db,1,"world",0,70,0,new GoodieFrameMarker(-1,2,2,3,"EAST",true));
        GoodieFrameStore.register(db,2,"world",32,70,0,new GoodieFrameMarker(-1,2,2,3,"EAST",true));
        GoodieFrameStore.register(db,3,"world",100,70,100,new GoodieFrameMarker(0,1,2,3,"NORTH",false));db.setAutoCommit(false);
    }
    @Test void takeAndPlaceRecoverThroughDatabaseReopenWithOneRevisionPerTransfer()throws Exception {
        String url="jdbc:sqlite:"+directory.resolve("transfers.sqlite");BonusGoodieStore.State source;
        try(var db=DriverManager.getConnection(url)) {
            schema(db);source=BonusGoodieStore.ensure(db,catalog,1);
            var intent=GoodieTransferStore.prepare(db,"take",player,source,BonusGoodieStore.Kind.PLAYER,player.toString(),null,new byte[]{1,2,3},10);db.commit();
            assertEquals(source,BonusGoodieStore.origin(db,1));assertTrue(GoodieTransferStore.lockedFrames(db).contains("1:-1"));
            byte[] copy=intent.inventory();copy[0]=99;assertArrayEquals(new byte[]{1,2,3},intent.inventory());
        }
        try(var db=DriverManager.getConnection(url)) {
            db.setAutoCommit(false);assertArrayEquals(new byte[]{1,2,3},GoodieTransferStore.pending(db).getFirst().inventory());
            assertEquals(GoodieTransferStore.Completion.COMPLETE,GoodieTransferStore.complete(db,"take"));db.commit();
            assertEquals(GoodieTransferStore.Completion.ALREADY_COMPLETE,GoodieTransferStore.complete(db,"take"));
            var held=BonusGoodieStore.origin(db,1);assertEquals(1,held.revision());assertEquals(BonusGoodieStore.Kind.PLAYER,held.kind());
            GoodieTransferStore.prepare(db,"place",player,held,BonusGoodieStore.Kind.FRAME,"3:0",2L,new byte[]{4},20);db.commit();
            assertTrue(BonusGoodieStore.framed(db,2).isEmpty());
            GoodieTransferStore.complete(db,"place");db.commit();
            assertEquals(2,BonusGoodieStore.framed(db,2).getFirst().revision());assertTrue(GoodieTransferStore.pending(db).isEmpty());
        }
    }
    @Test void competingPlayersCannotReserveSameFrameOrMoveAnotherPlayersItem()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);UUID other=UUID.randomUUID();
            var one=BonusGoodieStore.move(db,BonusGoodieStore.ensure(db,catalog,1),BonusGoodieStore.Kind.PLAYER,player.toString(),null);
            var two=BonusGoodieStore.move(db,BonusGoodieStore.ensure(db,catalog,2),BonusGoodieStore.Kind.PLAYER,other.toString(),null);db.commit();
            assertThrows(IllegalArgumentException.class,()->GoodieTransferStore.prepare(db,"wrong-player",player,two,BonusGoodieStore.Kind.FRAME,"3:0",2L,new byte[]{1},0));
            assertThrows(IllegalArgumentException.class,()->GoodieTransferStore.prepare(db,"wrong-town",player,one,BonusGoodieStore.Kind.FRAME,"3:0",1L,new byte[]{1},0));
            GoodieTransferStore.prepare(db,"one",player,one,BonusGoodieStore.Kind.FRAME,"3:0",2L,new byte[]{1},0);db.commit();
            assertThrows(IllegalArgumentException.class,()->GoodieTransferStore.prepare(db,"two",other,two,BonusGoodieStore.Kind.FRAME,"3:0",2L,new byte[]{1},0));db.rollback();
            assertThrows(IllegalArgumentException.class,()->GoodieTransferStore.requireOriginUnlocked(db,1));
            assertEquals(1,GoodieTransferStore.pending(db).size());
        }
    }
    @Test void destructionInvalidatesPendingItemWithoutResurrectingItsIdentity()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);var source=BonusGoodieStore.ensure(db,catalog,1);
            GoodieTransferStore.prepare(db,"take",player,source,BonusGoodieStore.Kind.PLAYER,player.toString(),null,new byte[]{1},0);db.commit();
            db.createStatement().execute("UPDATE structure_builds SET status='WAR_DESTROY_PENDING' WHERE id=1");db.commit();
            assertEquals(GoodieTransferStore.Completion.INVALIDATED,GoodieTransferStore.complete(db,"take"));db.commit();
            assertNull(BonusGoodieStore.token(db,source.token()));assertTrue(GoodieTransferStore.pending(db).isEmpty());assertTrue(GoodieTransferStore.lockedFrames(db).isEmpty());
            assertEquals(GoodieTransferStore.Completion.ALREADY_COMPLETE,GoodieTransferStore.complete(db,"take"));
        }
    }
    @Test void completionRollbackKeepsOriginalHolderAndRecoverableInventory()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);var source=BonusGoodieStore.ensure(db,catalog,1);
            GoodieTransferStore.prepare(db,"take",player,source,BonusGoodieStore.Kind.PLAYER,player.toString(),null,new byte[]{7},0);db.commit();
            GoodieTransferStore.complete(db,"take");db.rollback();
            assertEquals(source,BonusGoodieStore.origin(db,1));assertArrayEquals(new byte[]{7},GoodieTransferStore.pending(db).getFirst().inventory());
            GoodieTransferStore.complete(db,"take");db.commit();assertEquals(1,BonusGoodieStore.origin(db,1).revision());
        }
    }
}
