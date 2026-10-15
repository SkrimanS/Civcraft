package com.avrgaming.civcraft.modern.trade;

import java.io.File;
import java.sql.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradeDepositStoreTest {
    final TradeGoodsCatalog catalog=new TradeGoodsCatalog(new File("src/main/resources/civcraft/data/goods.yml"));
    Connection database()throws SQLException {
        var db=DriverManager.getConnection("jdbc:sqlite::memory:");
        db.createStatement().execute("PRAGMA foreign_keys=ON");
        db.createStatement().execute("CREATE TABLE structure_builds(id INTEGER PRIMARY KEY,status TEXT)");
        db.createStatement().execute("INSERT INTO structure_builds VALUES(1,'BUILDING'),(2,'BUILDING')");
        TradeDepositStore.install(db);return db;
    }
    @Test void originalCatalogPreservesWaterTypesPricesBuffsAndCaseSensitiveIds() {
        assertFalse(catalog.require("good_oil").water());assertEquals(375,catalog.require("good_oil").value());
        assertEquals(List.of("buff_advanced_tooling","buff_extraction","buff_fire_bomb"),catalog.require("good_oil").buffs());
        assertTrue(catalog.require("good_crabs").water());assertEquals("Меха",catalog.require("good_pelts").name());
        assertNotNull(catalog.require("good_Marble"));assertEquals(6,catalog.list().stream().filter(TradeGoodsCatalog.Good::water).count());
    }
    @Test void depositMustFitWithThreeBlocksAboveAndMatchOutpostTypeAndWorld()throws Exception {
        try(var db=database()) {
            TradeDepositStore.register(db,catalog,"good_crabs","world",-1,62,-1);
            assertThrows(IllegalArgumentException.class,()->TradeDepositStore.require(db,catalog,"ti_fishing_boat","other",-16,59,-16,16,7,16));
            assertThrows(IllegalArgumentException.class,()->TradeDepositStore.require(db,catalog,"ti_fishing_boat","world",-16,59,-16,16,6,16));
            var good=TradeDepositStore.require(db,catalog,"ti_fishing_boat","world",-16,59,-16,16,7,16);assertEquals("good_crabs",good.good());
            assertThrows(IllegalArgumentException.class,()->TradeDepositStore.require(db,catalog,"ti_trade_outpost","world",-16,59,-16,16,7,16));
            assertThrows(IllegalArgumentException.class,()->TradeDepositStore.require(db,catalog,"ti_fishing_boat","world",0,59,-16,16,7,16));
        }
    }
    @Test void atomicReservationRejectsStalePreviewsAndRetainsRuinsButReleasesDeletedBuilds()throws Exception {
        try(var db=database()) {
            TradeDepositStore.register(db,catalog,"good_pelts","world",1,70,1);
            var deposit=TradeDepositStore.require(db,catalog,"ti_trade_outpost","world",0,70,0,16,8,16);
            assertThrows(SQLException.class,()->TradeDepositStore.claim(db,deposit,1));db.setAutoCommit(false);
            TradeDepositStore.claim(db,deposit,1);db.rollback();
            assertDoesNotThrow(()->TradeDepositStore.require(db,catalog,"ti_trade_outpost","world",0,70,0,16,8,16));
            TradeDepositStore.claim(db,deposit,1);db.commit();
            assertThrows(IllegalArgumentException.class,()->TradeDepositStore.claim(db,deposit,2));db.rollback();
            db.createStatement().execute("UPDATE structure_builds SET status='WAR_DESTROYED' WHERE id=1");db.commit();
            assertThrows(IllegalArgumentException.class,()->TradeDepositStore.require(db,catalog,"ti_trade_outpost","world",0,70,0,16,8,16));
            db.createStatement().execute("UPDATE structure_builds SET status='DEMOLISHED' WHERE id=1");db.commit();
            TradeDepositStore.claim(db,deposit,2);db.commit();
            db.createStatement().execute("DELETE FROM structure_builds WHERE id=2");db.commit();
            assertNull(TradeDepositStore.require(db,catalog,"ti_trade_outpost","world",0,70,0,16,8,16).build());
        }
    }
}
