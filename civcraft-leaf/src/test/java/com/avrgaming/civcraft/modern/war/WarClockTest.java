package com.avrgaming.civcraft.modern.war;
import java.io.File;
import java.sql.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WarClockTest {
    final ZoneId moscow=ZoneId.of("Europe/Moscow");
    WarCalendar calendar(){return new WarCalendar(new File("src/main/resources/civcraft/data/war.yml"),moscow);}
    long at(int day,int hour,int minute){return ZonedDateTime.of(2026,10,day,hour,minute,0,0,moscow).toInstant().toEpochMilli();}
    Connection database()throws Exception {
        var db=DriverManager.getConnection("jdbc:sqlite::memory:");db.createStatement().execute("CREATE TABLE diplomacy_relations(first_civ INTEGER,second_civ INTEGER,status TEXT)");WarClockStore.install(db);db.setAutoCommit(false);return db;
    }
    void war(Connection db)throws Exception{db.createStatement().execute("INSERT INTO diplomacy_relations VALUES(1,2,'WAR')");db.commit();}
    @Test void shippedCalendarUsesSaturdayTenAndTwoHoursWithExactBoundaries() {
        var c=calendar();assertEquals(at(17,10,0),c.next(Instant.ofEpochMilli(at(14,9,0))).toEpochMilli());
        var w=c.current(Instant.ofEpochMilli(at(17,10,0)));assertEquals(at(17,10,0),w.start().toEpochMilli());assertEquals(at(17,12,0),w.end().toEpochMilli());
        assertTrue(w.contains(w.start()));assertFalse(w.contains(w.end()));assertEquals(Duration.ofMinutes(120),Duration.between(w.start(),w.end()));
    }
    @Test void declarationAndAllyWindowsMatchOriginalInclusiveLimits() {
        var c=calendar();assertFalse(c.declarationClosed(Instant.ofEpochMilli(at(14,10,0)-1)));assertTrue(c.declarationClosed(Instant.ofEpochMilli(at(14,10,0))));
        assertFalse(c.allyDeclarationClosed(Instant.ofEpochMilli(at(16,10,0)-1)));assertTrue(c.allyDeclarationClosed(Instant.ofEpochMilli(at(16,10,0))));
    }
    @Test void calendarSundayMappingAndDstKeepElapsedDurationAndLocalWeeklyHour() {
        var zone=ZoneId.of("Europe/Berlin");var c=new WarCalendar(1,2,120,3,24,zone);var now=Instant.parse("2026-10-25T00:30:00Z");var w=c.current(now);
        assertEquals(DayOfWeek.SUNDAY,w.start().atZone(zone).getDayOfWeek());assertEquals(2,w.start().atZone(zone).getHour());
        assertEquals(Duration.ofHours(2),Duration.between(w.start(),w.end()));assertEquals(2,c.next(now).atZone(zone).getHour());
    }
    @Test void activeSessionSurvivesRestartWithoutExtendingItsEndAndClosesExactlyOnce()throws Exception {
        try(var db=database()) {
            war(db);var start=WarClockStore.synchronize(db,calendar(),at(17,10,0));db.commit();assertEquals("ACTIVE",start.getFirst().state());assertTrue(WarClockStore.active(db,at(17,10,0)));
            assertTrue(WarClockStore.synchronize(db,calendar(),at(17,11,0)).isEmpty());db.commit();
            var closed=WarClockStore.synchronize(db,calendar(),at(17,12,0));db.commit();assertEquals(1,closed.size());assertEquals("CLOSED",closed.getFirst().state());assertEquals(at(17,12,0),closed.getFirst().end());
            assertFalse(WarClockStore.active(db,at(17,12,0)));assertTrue(WarClockStore.synchronize(db,calendar(),at(17,13,0)).isEmpty());
        }
    }
    @Test void noWarsSkipsSessionAndLateDeclarationCannotStartAnUnscheduledBattle()throws Exception {
        try(var db=database()) {
            assertEquals("SKIPPED",WarClockStore.synchronize(db,calendar(),at(17,10,0)).getFirst().state());db.commit();war(db);
            assertTrue(WarClockStore.synchronize(db,calendar(),at(17,11,0)).isEmpty());db.commit();assertFalse(WarClockStore.active(db,at(17,11,0)));
            assertEquals("ACTIVE",WarClockStore.synchronize(db,calendar(),at(24,10,0)).getFirst().state());db.commit();assertTrue(WarClockStore.active(db,at(24,10,0)));
        }
    }
    @Test void missedExpiredWindowDoesNotStartFreshWarAfterOfflineTime()throws Exception {
        try(var db=database()) {
            war(db);assertTrue(WarClockStore.synchronize(db,calendar(),at(17,13,0)).isEmpty());db.commit();assertFalse(WarClockStore.active(db,at(17,13,0)));
            assertEquals(at(24,10,0),calendar().next(Instant.ofEpochMilli(at(17,13,0))).toEpochMilli());
        }
    }
    @Test void diplomacyGuardAndTransactionRollbackDoNotLoseOrDuplicateSession()throws Exception {
        try(var db=database()) {
            war(db);assertThrows(IllegalArgumentException.class,()->WarClockStore.requireDiplomacyOpen(db,calendar(),at(17,10,0)));db.rollback();assertFalse(WarClockStore.active(db,at(17,10,0)));
            WarClockStore.synchronize(db,calendar(),at(17,10,0));db.rollback();assertFalse(WarClockStore.active(db,at(17,10,0)));
            WarClockStore.synchronize(db,calendar(),at(17,10,0));db.commit();assertTrue(WarClockStore.hasWars(db,1));assertFalse(WarClockStore.hasWars(db,3));
            assertThrows(IllegalArgumentException.class,()->WarClockStore.requireDiplomacyOpen(db,calendar(),at(17,11,0)));db.rollback();
            assertThrows(IllegalArgumentException.class,()->WarClockStore.requireDiplomacyOpen(db,calendar(),at(17,12,0)));db.rollback();
            WarClockStore.synchronize(db,calendar(),at(17,12,0));db.commit();WarClockStore.requireDiplomacyOpen(db,calendar(),at(17,12,0));assertFalse(WarClockStore.active(db,at(17,12,0)));
        }
    }
}
