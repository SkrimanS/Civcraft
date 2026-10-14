package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.time.Instant;
import java.util.*;
/** Immutable scheduled boundaries; skipped sessions never activate from a late declaration. */
public final class WarClockStore {
    private WarClockStore() {}
    public record Event(long start,long end,String state) {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS war_sessions(start_at INTEGER PRIMARY KEY,end_at INTEGER NOT NULL CHECK(end_at>start_at),state TEXT NOT NULL CHECK(state IN ('ACTIVE','SKIPPED','CLOSED')))");}
    }
    public static boolean active(Connection db,long now)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM war_sessions WHERE state='ACTIVE' AND start_at<=? AND end_at>? LIMIT 1")) {
            s.setLong(1,now);s.setLong(2,now);try(var r=s.executeQuery()){return r.next();}
        }
    }
    public static boolean hasWars(Connection db,long civ)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM diplomacy_relations WHERE status='WAR' AND (first_civ=? OR second_civ=?) LIMIT 1")) {
            s.setLong(1,civ);s.setLong(2,civ);try(var r=s.executeQuery()){return r.next();}
        }
    }
    public static Long activeEnd(Connection db,long now)throws SQLException {
        try(var s=db.prepareStatement("SELECT end_at FROM war_sessions WHERE state='ACTIVE' AND start_at<=? AND end_at>? ORDER BY end_at LIMIT 1")) {
            s.setLong(1,now);s.setLong(2,now);try(var r=s.executeQuery()){return r.next()?r.getLong(1):null;}
        }
    }
    public static List<Event> synchronize(Connection db,WarCalendar calendar,long now)throws SQLException {
        var events=new ArrayList<Event>();
        try(var s=db.prepareStatement("SELECT start_at,end_at FROM war_sessions WHERE state='ACTIVE' AND end_at<=?")) {
            s.setLong(1,now);try(var r=s.executeQuery()){while(r.next())events.add(new Event(r.getLong(1),r.getLong(2),"CLOSED"));}
        }
        try(var s=db.prepareStatement("UPDATE war_sessions SET state='CLOSED' WHERE state='ACTIVE' AND end_at<=?")){s.setLong(1,now);s.executeUpdate();}
        var window=calendar.current(Instant.ofEpochMilli(now));
        if(window.contains(Instant.ofEpochMilli(now))) {
            boolean wars;
            try(var s=db.createStatement();var r=s.executeQuery("SELECT EXISTS(SELECT 1 FROM diplomacy_relations WHERE status='WAR')")){r.next();wars=r.getBoolean(1);}
            String state=wars?"ACTIVE":"SKIPPED";
            try(var s=db.prepareStatement("INSERT OR IGNORE INTO war_sessions VALUES(?,?,?)")) {
                s.setLong(1,window.start().toEpochMilli());s.setLong(2,window.end().toEpochMilli());s.setString(3,state);
                if(s.executeUpdate()==1)events.add(new Event(window.start().toEpochMilli(),window.end().toEpochMilli(),state));
            }
        }
        return List.copyOf(events);
    }
    public static void requireDiplomacyOpen(Connection db,WarCalendar calendar,long now)throws SQLException {
        // Only the clock owns session transitions and their end-of-war callbacks.
        // Fence even an expired ACTIVE row until that clock has processed it.
        boolean blocked;
        try(var s=db.prepareStatement("SELECT EXISTS(SELECT 1 FROM war_sessions WHERE state='ACTIVE' AND start_at<=?)")) {
            s.setLong(1,now);try(var r=s.executeQuery()){r.next();blocked=r.getBoolean(1);}
        }
        var window=calendar.current(Instant.ofEpochMilli(now));
        if(!blocked && window.contains(Instant.ofEpochMilli(now)))try(var s=db.prepareStatement("SELECT NOT EXISTS(SELECT 1 FROM war_sessions WHERE start_at=?) AND EXISTS(SELECT 1 FROM diplomacy_relations WHERE status='WAR')")) {
            s.setLong(1,window.start().toEpochMilli());try(var r=s.executeQuery()){r.next();blocked=r.getBoolean(1);}
        }
        if(blocked)throw new IllegalArgumentException("Во время боевого периода менять дипломатические отношения нельзя.");
    }
}
