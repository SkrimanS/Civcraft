package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.util.*;
/** End-of-war ownership and results are committed together and replay safely. */
public final class ConquestStore {
    private ConquestStore() {}
    public record Result(long war,int towns,int civilizations,boolean casual) {}
    public static void install(Connection db)throws SQLException {
        OutlawStore.install(db);
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS war_results(war_start INTEGER PRIMARY KEY REFERENCES war_sessions(start_at),casual INTEGER NOT NULL,processed_at INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS town_conquests(town_id INTEGER PRIMARY KEY REFERENCES towns(id) ON DELETE CASCADE,mother_civ INTEGER REFERENCES civilizations(id),changed_at INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS civ_conquests(civ_id INTEGER PRIMARY KEY REFERENCES civilizations(id) ON DELETE CASCADE,conquered_at INTEGER NOT NULL)");
            // Historical identifiers must not prevent future deletion of a town or civilization.
            String audit="CREATE TABLE IF NOT EXISTS war_town_transfers(war_start INTEGER NOT NULL REFERENCES war_sessions(start_at),town_id INTEGER NOT NULL,from_civ INTEGER NOT NULL,to_civ INTEGER NOT NULL,PRIMARY KEY(war_start,town_id))";
            s.executeUpdate(audit);
            boolean old;try(var r=s.executeQuery("SELECT sql FROM sqlite_master WHERE name='war_town_transfers'")){r.next();old=r.getString(1).contains("REFERENCES towns");}
            if(old){s.executeUpdate("ALTER TABLE war_town_transfers RENAME TO war_town_transfers_before_audit");s.executeUpdate(audit);s.executeUpdate("INSERT INTO war_town_transfers SELECT * FROM war_town_transfers_before_audit");s.executeUpdate("DROP TABLE war_town_transfers_before_audit");}
        }
    }
    public static boolean conquered(Connection db,long civ)throws SQLException {try(var s=db.prepareStatement("SELECT 1 FROM civ_conquests WHERE civ_id=?")){s.setLong(1,civ);try(var r=s.executeQuery()){return r.next();}}}
    private static boolean transfer(Connection db,long war,long town,long winner,long now)throws SQLException {
        long old;Long mother;
        try(var s=db.prepareStatement("SELECT t.civ_id,c.mother_civ FROM towns t LEFT JOIN town_conquests c ON c.town_id=t.id WHERE t.id=?")){s.setLong(1,town);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Captured town disappeared");old=r.getLong(1);mother=r.getObject(2)==null?null:r.getLong(2);}}
        if(old==winner)return false;
        if(conquered(db,winner))throw new SQLException("Capture winner is dormant");
        Long origin;if(mother==null)origin=old;else if(mother==winner)origin=null;else origin=mother;
        try(var s=db.prepareStatement("INSERT INTO town_conquests VALUES(?,?,?) ON CONFLICT(town_id) DO UPDATE SET mother_civ=excluded.mother_civ,changed_at=excluded.changed_at")){s.setLong(1,town);if(origin==null)s.setNull(2,Types.BIGINT);else s.setLong(2,origin);s.setLong(3,now);s.executeUpdate();}
        try(var s=db.prepareStatement("UPDATE towns SET civ_id=? WHERE id=?")){s.setLong(1,winner);s.setLong(2,town);s.executeUpdate();}
        try(var s=db.prepareStatement("UPDATE town_claims SET civ_id=? WHERE town_id=?")){s.setLong(1,winner);s.setLong(2,town);s.executeUpdate();}
        // Resident membership is explicit in the new storage; legacy derives it from the town.
        try(var s=db.prepareStatement("UPDATE residents SET civ_id=?,updated_at=? WHERE town_id=?")){s.setLong(1,winner);s.setLong(2,now);s.setLong(3,town);s.executeUpdate();}
        OutlawStore.removeNewCivilizationMembers(db,town,winner);
        try(var s=db.prepareStatement("INSERT INTO war_town_transfers VALUES(?,?,?,?)")){s.setLong(1,war);s.setLong(2,town);s.setLong(3,old);s.setLong(4,winner);s.executeUpdate();}return true;
    }
    public static List<Result> processClosed(Connection db,boolean casual,long now)throws SQLException {
        CapitalStore.seed(db);
        var wars=new ArrayList<Long>();try(var s=db.createStatement();var r=s.executeQuery("SELECT w.start_at FROM war_sessions w LEFT JOIN war_results r ON r.war_start=w.start_at WHERE w.state='CLOSED' AND r.war_start IS NULL ORDER BY w.start_at")){while(r.next())wars.add(r.getLong(1));}
        var results=new ArrayList<Result>();
        for(long war:wars) {
            record Victory(long loser,long winner) {}
            var towns=new ArrayList<Victory>();var civs=new ArrayList<Victory>();
            try(var s=db.prepareStatement("SELECT town_id,conqueror FROM war_defeated_towns WHERE war_start=? ORDER BY town_id")){s.setLong(1,war);try(var r=s.executeQuery()){while(r.next())towns.add(new Victory(r.getLong(1),r.getLong(2)));}}
            try(var s=db.prepareStatement("SELECT civ_id,conqueror FROM war_defeated_civs WHERE war_start=? ORDER BY civ_id")){s.setLong(1,war);try(var r=s.executeQuery()){while(r.next())civs.add(new Victory(r.getLong(1),r.getLong(2)));}}
            int moved=0;
            if(!casual) {
                for(var v:towns)if(transfer(db,war,v.loser(),v.winner(),now))moved++;
                for(var v:civs) {
                    var remaining=new ArrayList<Long>();try(var s=db.prepareStatement("SELECT id FROM towns WHERE civ_id=? ORDER BY id")){s.setLong(1,v.loser());try(var r=s.executeQuery()){while(r.next())remaining.add(r.getLong(1));}}
                    for(long town:remaining)if(transfer(db,war,town,v.winner(),now))moved++;
                    try(var s=db.prepareStatement("INSERT INTO civ_conquests VALUES(?,?) ON CONFLICT(civ_id) DO NOTHING")){s.setLong(1,v.loser());s.setLong(2,now);s.executeUpdate();}
                    try(var s=db.prepareStatement("DELETE FROM diplomacy_relations WHERE first_civ=? OR second_civ=?")){s.setLong(1,v.loser());s.setLong(2,v.loser());s.executeUpdate();}
                    try(var s=db.prepareStatement("UPDATE diplomacy_requests SET state='CANCELLED' WHERE state='PENDING' AND (from_civ=? OR to_civ=?)")){s.setLong(1,v.loser());s.setLong(2,v.loser());s.executeUpdate();}
                    try(var s=db.prepareStatement("DELETE FROM civ_advisors WHERE civ_id=?")){s.setLong(1,v.loser());s.executeUpdate();}
                    try(var s=db.prepareStatement("DELETE FROM settlement_invites WHERE kind='CIV' AND settlement_id=?")){s.setLong(1,v.loser());s.executeUpdate();}
                    try(var s=db.prepareStatement("UPDATE residents SET civ_id=NULL,updated_at=? WHERE civ_id=? AND town_id IS NULL")){s.setLong(1,now);s.setLong(2,v.loser());s.executeUpdate();}
                }
            }else for(var v:civs)try(var s=db.prepareStatement("UPDATE diplomacy_relations SET status='NEUTRAL',aggressor=NULL,changed_at=? WHERE first_civ=? AND second_civ=?")){s.setLong(1,now);s.setLong(2,Math.min(v.loser(),v.winner()));s.setLong(3,Math.max(v.loser(),v.winner()));s.executeUpdate();}
            try(var s=db.prepareStatement("INSERT INTO war_results VALUES(?,?,?)")){s.setLong(1,war);s.setBoolean(2,casual);s.setLong(3,now);s.executeUpdate();}
            results.add(new Result(war,moved,civs.size(),casual));
        }
        // Legacy battle flags and pending winners are cleared after applying the result.
        // The permanent transfer ledger survives without pinning town/civ records.
        for(String table:new String[]{"war_defeated_towns","war_defeated_civs","war_inactive_towns"})try(var s=db.createStatement()){s.executeUpdate("DELETE FROM "+table+" WHERE war_start IN (SELECT war_start FROM war_results)");}
        return List.copyOf(results);
    }
}
