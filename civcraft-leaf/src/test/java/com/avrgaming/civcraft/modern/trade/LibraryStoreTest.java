package com.avrgaming.civcraft.modern.trade;

import java.sql.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LibraryStoreTest {
    @TempDir Path directory;
    @Test void everyShippedResearchHasValidatedPriceLevelAndRussianName() {
        var catalog=new com.avrgaming.civcraft.modern.town.TownCatalog(new java.io.File("src/main/resources/civcraft/data/town.yml"));
        var offers=catalog.upgrades().stream().filter(u->u.action().equals("enable_library_enchantment")).map(u->LibraryOffer.parse(u.value())).toList();
        assertEquals(11,offers.size());assertTrue(offers.stream().allMatch(o->o.name().matches(".*[А-Яа-яЁё].*")));
        assertEquals(new LibraryOffer("efficiency",1,15),LibraryOffer.parse("Efficiency, 1, 15"));
        assertEquals(30000,offers.stream().filter(LibraryOffer::soulbound).findFirst().orElseThrow().price());
        for(String invalid:java.util.List.of("Unknown,1,50","Efficiency,6,15","Looting,0,1","Fortune,1,-5","Silk_Touch,1,0.5"))assertThrows(IllegalArgumentException.class,()->LibraryOffer.parse(invalid));
    }
    @Test void orderedOffersAndLevelSurviveConnectionReopenAndCascadeWithTown()throws Exception {
        String url="jdbc:sqlite:"+directory.resolve("library.db");
        try(var db=DriverManager.getConnection(url)) {
            db.createStatement().execute("PRAGMA foreign_keys=ON");db.createStatement().execute("CREATE TABLE towns(id INTEGER PRIMARY KEY)");db.createStatement().execute("INSERT INTO towns VALUES(1)");
            LibraryStore.install(db);LibraryStore.level(db,1,4);LibraryStore.level(db,1,2);
            db.createStatement().execute("UPDATE town_library_settings SET fee=.2");
            LibraryStore.add(db,1,LibraryOffer.parse("Silk_Touch,1,1000"));LibraryStore.add(db,1,LibraryOffer.parse("Efficiency,5,1500"));
        }
        try(var db=DriverManager.getConnection(url)) {
            db.createStatement().execute("PRAGMA foreign_keys=ON");LibraryStore.install(db);
            assertEquals("silk_touch",LibraryStore.offer(db,1,0).enchantment());assertEquals(5,LibraryStore.offer(db,1,1).level());assertNull(LibraryStore.offer(db,1,2));assertEquals(.2,LibraryStore.fee(db,1));
            try(var r=db.createStatement().executeQuery("SELECT level FROM town_library_settings")){assertTrue(r.next());assertEquals(4,r.getInt(1));}
            db.createStatement().execute("DELETE FROM towns WHERE id=1");assertNull(LibraryStore.offer(db,1,0));
        }
    }
}
