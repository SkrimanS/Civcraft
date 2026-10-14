package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LegacyScoreStoreTest {
    final RevolutionRules rules=new RevolutionRules(new java.io.File("src/main/resources/civcraft/data"));
    Connection database()throws Exception {var db=DriverManager.getConnection("jdbc:sqlite::memory:");try(var s=db.createStatement()){
        s.execute("CREATE TABLE civilizations(id INTEGER PRIMARY KEY,coins INTEGER)");s.execute("INSERT INTO civilizations VALUES(1,1000000000),(2,0)");s.execute("CREATE TABLE towns(id INTEGER,civ_id INTEGER,coins INTEGER)");s.execute("INSERT INTO towns VALUES(1,1,1000),(2,2,1000)");
        s.execute("CREATE TABLE administrative_civilizations(civ_id INTEGER)");s.execute("CREATE TABLE residents(town_id INTEGER)");s.execute("INSERT INTO residents VALUES(1),(2)");s.execute("CREATE TABLE town_claims(town_id INTEGER)");s.execute("INSERT INTO town_claims VALUES(1),(1),(2),(2)");s.execute("CREATE TABLE culture_regions(town_id INTEGER)");s.execute("INSERT INTO culture_regions VALUES(1),(1),(1),(2),(2),(2)");s.execute("CREATE TABLE structure_builds(town_id INTEGER,structure_id TEXT,status TEXT)");s.execute("CREATE TABLE civ_technologies(civ_id INTEGER,tech_id TEXT)");
    }LegacyScoreStore.install(db);return db;}
    @Test void scoreUsesTownCoinsResidentsClaimsCultureAndExcludesCivilizationTreasury()throws Exception {try(var db=database()){assertEquals(5900,rules.townScore(db,1));assertEquals(5900,rules.civilizationScore(db,1));}}
    @Test void sameScoreRetainsOneRankEntryAndSnapshotChangesOnlyAfterRefresh()throws Exception {try(var db=database()){LegacyScoreStore.refresh(db,rules,1);assertEquals(0,LegacyScoreStore.score(db,1));assertEquals(5900,LegacyScoreStore.score(db,2));db.createStatement().execute("UPDATE towns SET coins=1005 WHERE id=1");assertEquals(0,LegacyScoreStore.score(db,1));LegacyScoreStore.refresh(db,rules,2);assertEquals(5901,LegacyScoreStore.score(db,1));assertEquals(5900,LegacyScoreStore.score(db,2));}}
    @Test void administrativeCivilizationsAreNotRankedAndUnknownCivilizationHasZero()throws Exception {try(var db=database()){db.createStatement().execute("INSERT INTO administrative_civilizations VALUES(2)");LegacyScoreStore.refresh(db,rules,1);assertEquals(5900,LegacyScoreStore.score(db,1));assertEquals(0,LegacyScoreStore.score(db,2));assertEquals(0,LegacyScoreStore.score(db,99));}}
}
