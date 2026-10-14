package com.avrgaming.civcraft.modern.build;

import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StructureCapitalRulesTest {
    private Connection database() throws SQLException {
        var db = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var s = db.createStatement()) {
            s.execute("CREATE TABLE civilizations(id INTEGER,capital_town_id INTEGER)");
            s.execute("CREATE TABLE towns(id INTEGER,civ_id INTEGER)");
            s.execute("CREATE TABLE structure_builds(town_id INTEGER,structure_id TEXT,status TEXT)");
            s.execute("INSERT INTO civilizations VALUES(1,1),(2,NULL)");
            s.execute("INSERT INTO towns VALUES(1,1),(2,1),(3,2),(4,2)");
        }
        return db;
    }

    @Test void hallTypeFollowsCurrentCapital() throws Exception {
        try (var db = database()) {
            assertDoesNotThrow(() -> StructureCapitalRules.require(db,1,"s_capitol"));
            assertThrows(IllegalArgumentException.class,() -> StructureCapitalRules.require(db,1,"s_townhall"));
            assertDoesNotThrow(() -> StructureCapitalRules.require(db,2,"s_townhall"));
            assertThrows(IllegalArgumentException.class,() -> StructureCapitalRules.require(db,2,"s_capitol"));
            db.createStatement().execute("UPDATE civilizations SET capital_town_id=2 WHERE id=1");
            assertDoesNotThrow(() -> StructureCapitalRules.require(db,2,"s_capitol"));
            assertThrows(IllegalArgumentException.class,() -> StructureCapitalRules.require(db,1,"s_capitol"));
        }
    }

    @Test void unsetCapitalUsesFirstTownOfItsOwnCivilization() throws Exception {
        try (var db = database()) {
            assertDoesNotThrow(() -> StructureCapitalRules.require(db,3,"s_capitol"));
            assertThrows(IllegalArgumentException.class,() -> StructureCapitalRules.require(db,4,"s_capitol"));
        }
    }

    @Test void capitalWondersRejectNonCapitalAndRegisteredRuins() throws Exception {
        try (var db = database()) {
            for (String type : new String[]{"w_colosseum","w_battledome"}) {
                assertThrows(IllegalArgumentException.class,() -> StructureCapitalRules.require(db,2,type));
                for (String status : new String[]{"QUEUED","BUILDING","COMPLETE","WAR_DESTROYED"}) {
                    db.createStatement().execute("DELETE FROM structure_builds");
                    try (var s = db.prepareStatement("INSERT INTO structure_builds VALUES(1,?,?)")) {
                        s.setString(1,type); s.setString(2,status); s.executeUpdate();
                    }
                    assertThrows(IllegalArgumentException.class,() -> StructureCapitalRules.require(db,1,type));
                }
                db.createStatement().execute("UPDATE structure_builds SET status='DEMOLISHED'");
                assertDoesNotThrow(() -> StructureCapitalRules.require(db,1,type));
            }
            assertDoesNotThrow(() -> StructureCapitalRules.require(db,2,"w_chichen_itza"));
        }
    }

    @Test void requiredUpgradeCannotBeBorrowedFromAnotherTown() throws Exception {
        try (var db = database()) {
            db.createStatement().execute("CREATE TABLE town_upgrades(town_id INTEGER,upgrade_id TEXT)");
            assertDoesNotThrow(() -> StructurePrerequisites.requireUpgrade(db,1,"",""));
            db.createStatement().execute("INSERT INTO town_upgrades VALUES(2,'upgrade_grocer_level_2')");
            assertThrows(IllegalArgumentException.class,() -> StructurePrerequisites.requireUpgrade(db,1,"upgrade_grocer_level_2","Бакалея II"));
            db.createStatement().execute("INSERT INTO town_upgrades VALUES(1,'upgrade_grocer_level_2')");
            assertDoesNotThrow(() -> StructurePrerequisites.requireUpgrade(db,1,"upgrade_grocer_level_2","Бакалея II"));
            db.createStatement().execute("DELETE FROM town_upgrades WHERE town_id=1");
            assertThrows(IllegalArgumentException.class,() -> StructurePrerequisites.requireUpgrade(db,1,"upgrade_grocer_level_2","Бакалея II"));
        }
    }
}
