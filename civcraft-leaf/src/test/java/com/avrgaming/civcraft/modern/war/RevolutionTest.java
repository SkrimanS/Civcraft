package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class RevolutionTest {
    @TempDir Path folder;
    final OccupationActionsStoreTest f=new OccupationActionsStoreTest();
    final long now=Instant.parse("2026-10-12T10:00:00Z").toEpochMilli();
    final WarCalendar calendar=new WarCalendar(7,10,120,3,24,ZoneId.of("UTC"));
    RevolutionRules rules()throws Exception {
        Files.writeString(folder.resolve("score.yml"),"town_scores:\n  resident: 5000\n  town_chunk: 200\n  culture_chunk: 100\ncoins_per_point: 5.0\n");
        Files.writeString(folder.resolve("techs.yml"),"techs:\n - id: tech_test\n   points: 125\n");Files.writeString(folder.resolve("structures.yml"),"structures:\n - id: s_capitol\n   points: 1000\n");Files.writeString(folder.resolve("wonders.yml"),"wonders: []\n");Files.writeString(folder.resolve("war.yml"),"revolution:\n  base_cost: 50000\n  coins_per_town: 10000\n  coins_per_point: 0.1\n  maximum_fee: 1000000\n");Files.writeString(folder.resolve("civ.yml"),"civ:\n  revolution_cooldown: 2\n");return new RevolutionRules(folder.toFile());
    }
    Connection database()throws Exception {
        var db=f.database();try(var s=db.createStatement()) {
            s.execute("ALTER TABLE towns ADD COLUMN coins INTEGER DEFAULT 100000");s.execute("CREATE TABLE culture_regions(town_id INTEGER)");s.execute("INSERT INTO culture_regions VALUES(2),(3),(4)");
            s.execute("CREATE TABLE civ_technologies(civ_id INTEGER,tech_id TEXT)");s.execute("INSERT INTO civ_technologies VALUES(2,'tech_test')");
            s.execute("CREATE TABLE diplomacy_relations(first_civ INTEGER,second_civ INTEGER,status TEXT,aggressor INTEGER,changed_at INTEGER,PRIMARY KEY(first_civ,second_civ))");
            s.execute("CREATE TABLE diplomacy_requests(from_civ INTEGER,to_civ INTEGER,state TEXT)");s.execute("INSERT INTO diplomacy_requests VALUES(1,2,'PENDING')");
        }db.commit();return db;
    }
    @Test void feeIncludesTechnologiesBuildingsPeopleTerritoryAndCashAcrossAllCapturedOwners()throws Exception {
        try(var db=database()) {
            var fee=rules().fee(db,2);assertEquals(77025,fee.points());assertEquals(3,fee.towns());assertEquals(87703,fee.coins());
            assertEquals(1000000,RevolutionRules.price(50000,100,10000,100000000,.1,1000000));
        }
    }
    @Test void revolutionReturnsEveryCapturedCityAndDeclaresOwnersAsAggressorsOnce()throws Exception {
        try(var db=database()) {
            var rules=rules();var offered=OccupationActionsStore.revolutionOffer(db,f.motherLeader,rules,calendar,now);db.commit();assertEquals(100000,f.value(db,"SELECT coins FROM towns WHERE id=2"));
            var result=OccupationActionsStore.revolution(db,f.motherLeader,rules,calendar,now);db.commit();assertEquals(offered.fee(),result.fee());
            assertEquals(12297,f.value(db,"SELECT coins FROM towns WHERE id=2"));assertFalse(ConquestStore.conquered(db,2));assertEquals(3,f.value(db,"SELECT COUNT(*) FROM towns WHERE civ_id=2"));assertEquals(3,f.value(db,"SELECT COUNT(*) FROM town_claims WHERE civ_id=2"));
            assertEquals(0,f.value(db,"SELECT COUNT(*) FROM town_conquests WHERE mother_civ IS NOT NULL"));assertEquals(2,f.value(db,"SELECT COUNT(*) FROM diplomacy_relations WHERE status='WAR'"));
            assertEquals(1,f.value(db,"SELECT aggressor FROM diplomacy_relations WHERE first_civ=1 AND second_civ=2"));assertEquals(3,f.value(db,"SELECT aggressor FROM diplomacy_relations WHERE first_civ=2 AND second_civ=3"));assertEquals(0,f.value(db,"SELECT COUNT(*) FROM diplomacy_requests WHERE state='PENDING'"));
            assertThrows(IllegalArgumentException.class,()->OccupationActionsStore.revolution(db,f.motherLeader,rules,calendar,now+1));db.rollback();assertEquals(1,f.value(db,"SELECT COUNT(*) FROM occupation_actions"));
        }
    }
    @Test void cooldownIsStrictAndWarFreezeNonCapitalAndInsufficientMoneyCannotChangeAnything()throws Exception {
        try(var db=database()) {
            var rules=rules();db.createStatement().execute("UPDATE civ_conquests SET conquered_at="+(now-2*86400000L));db.commit();assertThrows(IllegalArgumentException.class,()->OccupationActionsStore.revolutionOffer(db,f.motherLeader,rules,calendar,now));db.rollback();
            db.createStatement().execute("UPDATE civ_conquests SET conquered_at=0");db.commit();assertThrows(IllegalArgumentException.class,()->OccupationActionsStore.revolutionOffer(db,f.member,rules,calendar,now));db.rollback();
            long frozen=Instant.parse("2026-10-16T10:00:00Z").toEpochMilli();assertThrows(IllegalArgumentException.class,()->OccupationActionsStore.revolutionOffer(db,f.motherLeader,rules,calendar,frozen));db.rollback();
            db.createStatement().execute("INSERT INTO war_sessions VALUES("+(now-1)+","+(now+10000)+",'ACTIVE')");db.commit();assertThrows(IllegalArgumentException.class,()->OccupationActionsStore.revolutionOffer(db,f.motherLeader,rules,calendar,now));db.rollback();db.createStatement().execute("UPDATE war_sessions SET state='CLOSED'");db.createStatement().execute("UPDATE towns SET coins=0 WHERE id=2");db.commit();
            assertThrows(IllegalArgumentException.class,()->OccupationActionsStore.revolution(db,f.motherLeader,rules,calendar,now));db.rollback();assertTrue(ConquestStore.conquered(db,2));assertEquals(0,f.value(db,"SELECT COUNT(*) FROM occupation_actions"));
        }
    }
    @Test void rollbackRestoresTreasuryOwnershipDormancyAndDiplomacyTogether()throws Exception {
        try(var db=database()) {
            OccupationActionsStore.revolution(db,f.motherLeader,rules(),calendar,now);db.rollback();assertTrue(ConquestStore.conquered(db,2));assertEquals(100000,f.value(db,"SELECT coins FROM towns WHERE id=2"));assertEquals(1,f.value(db,"SELECT civ_id FROM towns WHERE id=2"));assertEquals(3,f.value(db,"SELECT civ_id FROM towns WHERE id=4"));assertEquals(0,f.value(db,"SELECT COUNT(*) FROM diplomacy_relations"));
        }
    }
    @Test void cooldownUsesCalendarDaysAcrossDaylightSavingTime()throws Exception {
        var rules=rules();var zone=ZoneId.of("Europe/Berlin");long conquered=ZonedDateTime.of(2026,3,28,10,0,0,0,zone).toInstant().toEpochMilli();long deadline=ZonedDateTime.of(2026,3,30,10,0,0,0,zone).toInstant().toEpochMilli();assertFalse(rules.cooldownPassed(conquered,deadline,zone));assertTrue(rules.cooldownPassed(conquered,deadline+1,zone));
    }
}
