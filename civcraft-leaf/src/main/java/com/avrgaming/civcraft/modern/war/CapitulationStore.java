package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.util.*;
import com.avrgaming.civcraft.modern.diplomacy.DiplomacyStore;
public final class CapitulationStore {
    private CapitulationStore() {}
    public record Offer(long town,long mother,long owner,boolean whole,long captured,String townName,String motherName,String ownerName) {}
    public record Request(long id,Offer offer,String actor,long expires) {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            // Keep historical identifiers/names after deleting the capitulated mother civilization.
            s.executeUpdate("CREATE TABLE IF NOT EXISTS capitulation_requests(id INTEGER PRIMARY KEY AUTOINCREMENT,town_id INTEGER NOT NULL,mother_civ INTEGER NOT NULL,owner_civ INTEGER NOT NULL,whole INTEGER NOT NULL,capture_at INTEGER NOT NULL,town_name TEXT NOT NULL,mother_name TEXT NOT NULL,owner_name TEXT NOT NULL,actor TEXT NOT NULL,expires_at INTEGER NOT NULL,state TEXT NOT NULL CHECK(state IN ('PENDING','ACCEPTED','DECLINED','EXPIRED','CANCELLED')),responder TEXT)");
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_capitulation_question ON capitulation_requests(owner_civ) WHERE state='PENDING'");
        }
    }
    public static void expire(Connection db,long now)throws SQLException {try(var s=db.prepareStatement("UPDATE capitulation_requests SET state='EXPIRED' WHERE state='PENDING' AND expires_at<=?")){s.setLong(1,now);s.executeUpdate();}}
    public static Offer offer(Connection db,String name,UUID actor)throws SQLException {
        long town,mother,owner,captured;String townName,motherName,ownerName;
        try(var s=db.prepareStatement("SELECT t.id,tc.mother_civ,t.civ_id,tc.changed_at,t.name,m.name,c.name,m.leader_uuid,EXISTS(SELECT 1 FROM residents WHERE uuid=?) FROM towns t JOIN civilizations c ON c.id=t.civ_id LEFT JOIN town_conquests tc ON tc.town_id=t.id LEFT JOIN civilizations m ON m.id=tc.mother_civ WHERE t.name=? COLLATE NOCASE")) {
            s.setString(1,actor.toString());s.setString(2,name);try(var r=s.executeQuery()) {
                if(!r.next())throw new IllegalArgumentException("Город не найден: "+name);
                if(r.getObject(2)==null || r.getString(6)==null)throw new IllegalArgumentException("Этот город не захвачен.");
                if(!actor.toString().equals(r.getString(8)) || !r.getBoolean(9))throw new IllegalArgumentException("Капитуляцию предлагает лидер исходной цивилизации.");
                town=r.getLong(1);mother=r.getLong(2);owner=r.getLong(3);captured=r.getLong(4);townName=r.getString(5);motherName=r.getString(6);ownerName=r.getString(7);
            }
        }
        boolean whole=OccupationActionsStore.capital(db,mother)==town;
        if(whole) {
            if(!ConquestStore.conquered(db,mother))throw new IllegalArgumentException("Исходная цивилизация уже активна.");
            try(var s=db.prepareStatement("SELECT 1 FROM towns WHERE civ_id=?")){s.setLong(1,mother);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Исходная цивилизация ещё владеет городами.");}}
        }return new Offer(town,mother,owner,whole,captured,townName,motherName,ownerName);
    }
    public static Request incoming(Connection db,long owner,long now)throws SQLException {
        try(var s=db.prepareStatement("SELECT * FROM capitulation_requests WHERE owner_civ=? AND state='PENDING' AND expires_at>?")) {
            s.setLong(1,owner);s.setLong(2,now);try(var r=s.executeQuery()){if(!r.next())return null;return new Request(r.getLong("id"),new Offer(r.getLong("town_id"),r.getLong("mother_civ"),owner,r.getBoolean("whole"),r.getLong("capture_at"),r.getString("town_name"),r.getString("mother_name"),r.getString("owner_name")),r.getString("actor"),r.getLong("expires_at"));}
        }
    }
    public static Request request(Connection db,String town,UUID actor,long now)throws SQLException {
        var offer=offer(db,town,actor);expire(db,now);DiplomacyStore.expire(db,now);
        if(incoming(db,offer.owner(),now)!=null || DiplomacyStore.incoming(db,offer.owner(),now)!=null)throw new IllegalArgumentException("У владельца уже есть ожидающее дипломатическое предложение.");
        long expires=Math.addExact(now,30000);
        try(var s=db.prepareStatement("INSERT INTO capitulation_requests(town_id,mother_civ,owner_civ,whole,capture_at,town_name,mother_name,owner_name,actor,expires_at,state) VALUES(?,?,?,?,?,?,?,?,?,?,'PENDING') RETURNING id")) {
            s.setLong(1,offer.town());s.setLong(2,offer.mother());s.setLong(3,offer.owner());s.setBoolean(4,offer.whole());s.setLong(5,offer.captured());s.setString(6,offer.townName());s.setString(7,offer.motherName());s.setString(8,offer.ownerName());s.setString(9,actor.toString());s.setLong(10,expires);try(var r=s.executeQuery()){r.next();return new Request(r.getLong(1),offer,actor.toString(),expires);}
        }
    }
    public static Request respond(Connection db,long owner,UUID actor,boolean accept,long now)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM civilizations c JOIN residents r ON r.civ_id=c.id WHERE c.id=? AND r.uuid=? AND (c.leader_uuid=r.uuid OR EXISTS(SELECT 1 FROM civ_advisors a WHERE a.civ_id=c.id AND a.resident_uuid=r.uuid))")) {
            s.setLong(1,owner);s.setString(2,actor.toString());try(var r=s.executeQuery()){if(!r.next() || ConquestStore.conquered(db,owner))throw new IllegalArgumentException("На капитуляцию отвечает лидер или советник владельца.");}
        }
        expire(db,now);var request=incoming(db,owner,now);if(request==null)throw new IllegalArgumentException("Нет действующего предложения капитуляции.");var saved=request.offer();
        if(accept) {
            var current=offer(db,saved.townName(),UUID.fromString(request.actor()));
            if(current.town()!=saved.town() || current.mother()!=saved.mother() || current.owner()!=saved.owner() || current.whole()!=saved.whole() || current.captured()!=saved.captured())throw new IllegalArgumentException("Владение или захват изменились после предложения.");
            int affected;
            try(var s=db.prepareStatement(saved.whole()?"UPDATE town_conquests SET mother_civ=NULL,changed_at=? WHERE mother_civ=?":"UPDATE town_conquests SET mother_civ=NULL,changed_at=? WHERE town_id=? AND mother_civ=?")) {
                s.setLong(1,now);s.setLong(2,saved.whole()?saved.mother():saved.town());if(!saved.whole())s.setLong(3,saved.mother());affected=s.executeUpdate();
            }
            if(saved.whole()) {
                // Membership is explicit in the port. Legacy derives it from the current town.
                try(var s=db.prepareStatement("UPDATE residents SET civ_id=(SELECT t.civ_id FROM towns t WHERE t.id=residents.town_id),updated_at=? WHERE civ_id=?")){s.setLong(1,now);s.setLong(2,saved.mother());s.executeUpdate();}
                try(var s=db.prepareStatement("DELETE FROM civilizations WHERE id=?")){s.setLong(1,saved.mother());if(s.executeUpdate()!=1)throw new SQLException("Capitulated civilization disappeared");}
                try(var s=db.prepareStatement("UPDATE capitulation_requests SET state='CANCELLED' WHERE mother_civ=? AND state='PENDING' AND id<>?")){s.setLong(1,saved.mother());s.setLong(2,request.id());s.executeUpdate();}
            }
            try(var s=db.prepareStatement("INSERT INTO occupation_actions(action,actor,town_id,mother_civ,owner_civ,towns,at) VALUES('CAPITULATE',?,?,?,?,?,?)")){s.setString(1,request.actor());s.setLong(2,saved.town());s.setLong(3,saved.mother());s.setLong(4,owner);s.setInt(5,affected);s.setLong(6,now);s.executeUpdate();}
        }
        try(var s=db.prepareStatement("UPDATE capitulation_requests SET state=?,responder=? WHERE id=? AND state='PENDING'")){s.setString(1,accept?"ACCEPTED":"DECLINED");s.setString(2,actor.toString());s.setLong(3,request.id());if(s.executeUpdate()!=1)throw new SQLException("Capitulation was already answered");}return request;
    }
}
