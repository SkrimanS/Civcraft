package com.avrgaming.civcraft.modern.trade;

import java.io.File;
import java.sql.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BonusGoodieStoreTest {
    @TempDir Path directory;
    final TradeGoodsCatalog catalog=new TradeGoodsCatalog(new File("src/main/resources/civcraft/data/goods.yml"));
    void schema(Connection db)throws SQLException {
        db.createStatement().execute("PRAGMA foreign_keys=ON");
        db.createStatement().execute("CREATE TABLE towns(id INTEGER PRIMARY KEY)");db.createStatement().execute("INSERT INTO towns VALUES(1),(2)");
        db.createStatement().execute("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY,structure_id TEXT,status TEXT,max_hitpoints INTEGER)");
        db.createStatement().execute("CREATE TABLE building_combat_health(build_id INTEGER,hitpoints INTEGER)");
        db.createStatement().execute("INSERT INTO structure_builds VALUES(1,'ti_trade_outpost','COMPLETE',100),(2,'ti_fishing_boat','COMPLETE',100)");
        TradeDepositStore.install(db);BonusGoodieStore.install(db);
        TradeDepositStore.register(db,catalog,"good_pelts","world",1,64,1);TradeDepositStore.register(db,catalog,"good_crabs","world",33,63,1);
        db.createStatement().execute("UPDATE trade_deposits SET build_id=CASE good_id WHEN 'good_pelts' THEN 1 ELSE 2 END");db.setAutoCommit(false);
    }
    @Test void oneIdentityPersistsAcrossReopenWithoutReplacingCarriedItem()throws Exception {
        String url="jdbc:sqlite:"+directory.resolve("goodies.sqlite");BonusGoodieStore.State carried;
        try(var db=DriverManager.getConnection(url)) {
            schema(db);var source=BonusGoodieStore.ensure(db,catalog,1);
            assertEquals(source,BonusGoodieStore.ensure(db,catalog,1));
            carried=BonusGoodieStore.move(db,source,BonusGoodieStore.Kind.PLAYER,"player-id",null);db.commit();
        }
        try(var db=DriverManager.getConnection(url)) {
            db.setAutoCommit(false);assertEquals(carried,BonusGoodieStore.ensure(db,catalog,1));
            var framed=BonusGoodieStore.move(db,carried,BonusGoodieStore.Kind.FRAME,"frame-id",2L);db.commit();
            assertEquals(java.util.List.of(framed),BonusGoodieStore.framed(db,2));assertTrue(BonusGoodieStore.framed(db,1).isEmpty());
        }
    }
    @Test void staleMovesFailAndReturnInvalidatesOldIdentityWithRollback()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);var source=BonusGoodieStore.ensure(db,catalog,1);db.commit();
            var player=BonusGoodieStore.move(db,source,BonusGoodieStore.Kind.PLAYER,"player",null);db.commit();
            assertThrows(IllegalArgumentException.class,()->BonusGoodieStore.move(db,source,BonusGoodieStore.Kind.FRAME,"frame",1L));db.rollback();
            BonusGoodieStore.returnHome(db,player);db.rollback();assertEquals(player,BonusGoodieStore.token(db,source.token()));
            var returned=BonusGoodieStore.returnHome(db,player);db.commit();assertNull(BonusGoodieStore.token(db,source.token()));
            assertNotEquals(player.token(),returned.token());assertEquals(BonusGoodieStore.Kind.SOURCE,returned.kind());
        }
    }
    @Test void framesHoldOneGoodieAndDestroyedOutpostInvalidatesCarriedToken()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(db);var one=BonusGoodieStore.ensure(db,catalog,1);var two=BonusGoodieStore.ensure(db,catalog,2);db.commit();
            var framed=BonusGoodieStore.move(db,one,BonusGoodieStore.Kind.FRAME,"frame",2L);db.commit();
            assertThrows(SQLException.class,()->BonusGoodieStore.move(db,two,BonusGoodieStore.Kind.FRAME,"frame",2L));db.rollback();
            db.createStatement().execute("UPDATE structure_builds SET status='WAR_DESTROY_PENDING' WHERE id=1");db.commit();
            assertNull(BonusGoodieStore.token(db,framed.token()));assertTrue(BonusGoodieStore.framed(db,2).isEmpty());
            assertNotNull(BonusGoodieStore.token(db,two.token()));
            assertThrows(IllegalArgumentException.class,()->BonusGoodieStore.ensure(db,catalog,1));
        }
    }
}
