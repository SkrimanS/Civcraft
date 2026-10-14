package com.avrgaming.civcraft.modern.trade;
import java.io.File;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GrocerCatalogTest {
    @Test void originalOffersAndVisitorFeesArePreserved() {
        var catalog=new GrocerCatalog(new File("src/main/resources/civcraft/data/structures.yml"));
        assertEquals(new GrocerCatalog.Entry(1,350,0,1,8,"Жареная треска"),catalog.require(1));
        assertEquals(15,catalog.require(2).price());assertEquals(20,catalog.require(3).price());assertEquals(30,catalog.require(4).price());
        assertEquals(0,GrocerCatalog.fee(8,.05,false));assertEquals(1,GrocerCatalog.fee(15,.05,false));
        assertEquals(2,GrocerCatalog.fee(30,.05,false));assertEquals(0,GrocerCatalog.fee(30,.05,true));
        assertThrows(IllegalArgumentException.class,()->GrocerCatalog.fee(30,Double.NaN,false));
    }
    @Test void grocerSlotsAreDistinctFromOtherTradeMarkers() {
        assertEquals(new TradePoint("grocer","0"),TradePoint.parse(java.util.List.of("/sign","slot:0")).orElseThrow());
        assertTrue(TradePoint.parse(java.util.List.of("/sign","slot:-1")).isEmpty());
        assertTrue(TradePoint.parse(java.util.List.of("/sign","slot:4")).isEmpty());
        assertTrue(TradePoint.parse(java.util.List.of("/sign","slot:fish")).isEmpty());
    }
    @Test void levelPersistenceKeepsFeeAndNeverDowngradesOnRepeatedApplication()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            db.createStatement().execute("PRAGMA foreign_keys=ON");db.createStatement().execute("CREATE TABLE towns(id INTEGER PRIMARY KEY)");db.createStatement().execute("INSERT INTO towns VALUES(1)");GrocerStore.install(db);
            assertEquals(new GrocerStore.Settings(1,.05),GrocerStore.settings(db,1));
            GrocerStore.level(db,1,3);db.createStatement().execute("UPDATE town_grocer_settings SET fee=.2");GrocerStore.level(db,1,2);
            assertEquals(new GrocerStore.Settings(3,.2),GrocerStore.settings(db,1));
            assertThrows(IllegalArgumentException.class,()->GrocerStore.level(db,1,5));
        }
    }
}
