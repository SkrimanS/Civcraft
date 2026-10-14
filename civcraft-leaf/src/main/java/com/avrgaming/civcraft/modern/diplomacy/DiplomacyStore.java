package com.avrgaming.civcraft.modern.diplomacy;

import java.sql.*;
import java.util.*;

/** One canonical row per pair; all changes, request state and audit share a transaction. */
public final class DiplomacyStore {
    private DiplomacyStore() {}
    public enum Status {
        NEUTRAL("Нейтралитет"),HOSTILE("Враждебность"),WAR("Война"),PEACE("Мир"),ALLY("Союз");
        private final String title;Status(String title){this.title=title;}public String title(){return title;}
    }
    public record Relation(long first,long second,Status status,Long aggressor,long changedAt) {}
    public record Request(long id,long from,long to,Status status,Status before,long expires) {}
    public static void install(Connection db)throws SQLException {
        com.avrgaming.civcraft.modern.war.CapitulationStore.install(db);
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS civ_advisors(civ_id INTEGER NOT NULL REFERENCES civilizations(id) ON DELETE CASCADE,resident_uuid TEXT NOT NULL REFERENCES residents(uuid) ON DELETE CASCADE,PRIMARY KEY(civ_id,resident_uuid))");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS remove_civ_advisor_on_leave AFTER UPDATE OF civ_id ON residents WHEN OLD.civ_id IS NOT NEW.civ_id BEGIN DELETE FROM civ_advisors WHERE resident_uuid=NEW.uuid; END");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS diplomacy_relations(first_civ INTEGER NOT NULL REFERENCES civilizations(id) ON DELETE CASCADE,second_civ INTEGER NOT NULL REFERENCES civilizations(id) ON DELETE CASCADE,status TEXT NOT NULL,aggressor INTEGER REFERENCES civilizations(id) ON DELETE SET NULL,changed_at INTEGER NOT NULL,PRIMARY KEY(first_civ,second_civ),CHECK(first_civ<second_civ))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS diplomacy_requests(id INTEGER PRIMARY KEY AUTOINCREMENT,from_civ INTEGER NOT NULL REFERENCES civilizations(id) ON DELETE CASCADE,to_civ INTEGER NOT NULL REFERENCES civilizations(id) ON DELETE CASCADE,status TEXT NOT NULL,before_status TEXT NOT NULL,expires_at INTEGER NOT NULL,state TEXT NOT NULL DEFAULT 'PENDING',CHECK(from_civ!=to_civ))");
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_diplomatic_question ON diplomacy_requests(to_civ) WHERE state='PENDING'");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS diplomacy_audit(id INTEGER PRIMARY KEY AUTOINCREMENT,first_civ INTEGER NOT NULL,second_civ INTEGER NOT NULL,before_status TEXT NOT NULL,after_status TEXT NOT NULL,actor_uuid TEXT NOT NULL,changed_at INTEGER NOT NULL)");
        }
    }
    private static void pair(long first,long second) {
        if(first<1 || second<1 || first==second)throw new IllegalArgumentException("Нельзя изменить отношения с собственной цивилизацией.");
    }
    public static Relation relation(Connection db,long first,long second)throws SQLException {
        pair(first,second);long low=Math.min(first,second),high=Math.max(first,second);
        try(var s=db.prepareStatement("SELECT status,aggressor,changed_at FROM diplomacy_relations WHERE first_civ=? AND second_civ=?")) {
            s.setLong(1,low);s.setLong(2,high);try(var r=s.executeQuery()) {
                if(!r.next())return new Relation(low,high,Status.NEUTRAL,null,0);
                long aggressor=r.getLong(2);boolean absent=r.wasNull();
                return new Relation(low,high,Status.valueOf(r.getString(1)),absent?null:aggressor,r.getLong(3));
            }
        }
    }
    public static Status status(Connection db,long first,long second)throws SQLException {
        if(first<1 || second<1)throw new IllegalArgumentException("Invalid civilization ID");
        return first==second?Status.ALLY:relation(db,first,second).status();
    }
    public static void authority(Connection db,long civ,UUID actor)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM civilizations c JOIN residents r ON r.civ_id=c.id WHERE c.id=? AND r.uuid=? AND (c.leader_uuid=r.uuid OR EXISTS(SELECT 1 FROM civ_advisors a WHERE a.civ_id=c.id AND a.resident_uuid=r.uuid))")) {
            s.setLong(1,civ);s.setString(2,actor.toString());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Дипломатией управляет лидер или советник цивилизации.");}
        }
    }
    public static void advisor(Connection db,long civ,UUID leader,UUID member,boolean add)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM civilizations WHERE id=? AND leader_uuid=?")) {
            s.setLong(1,civ);s.setString(2,leader.toString());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Советников назначает лидер цивилизации.");}
        }
        if(add) {
            try(var s=db.prepareStatement("INSERT OR IGNORE INTO civ_advisors SELECT ?,uuid FROM residents WHERE uuid=? AND civ_id=?")) {
                s.setLong(1,civ);s.setString(2,member.toString());s.setLong(3,civ);
                if(s.executeUpdate()!=1)throw new IllegalArgumentException("Советник должен быть участником цивилизации и ещё не иметь этой роли.");
            }
        }else try(var s=db.prepareStatement("DELETE FROM civ_advisors WHERE civ_id=? AND resident_uuid=?")){s.setLong(1,civ);s.setString(2,member.toString());if(s.executeUpdate()!=1)throw new IllegalArgumentException("Этот игрок не является советником.");}
    }
    public static void expire(Connection db,long now)throws SQLException {
        try(var s=db.prepareStatement("UPDATE diplomacy_requests SET state='EXPIRED' WHERE state='PENDING' AND expires_at<=?")){s.setLong(1,now);s.executeUpdate();}
    }
    public static Request incoming(Connection db,long civ,long now)throws SQLException {
        try(var s=db.prepareStatement("SELECT * FROM diplomacy_requests WHERE to_civ=? AND state='PENDING' AND expires_at>?")) {
            s.setLong(1,civ);s.setLong(2,now);try(var r=s.executeQuery()){return r.next()?new Request(r.getLong("id"),r.getLong("from_civ"),civ,Status.valueOf(r.getString("status")),Status.valueOf(r.getString("before_status")),r.getLong("expires_at")):null;}
        }
    }
    public static Request request(Connection db,long from,long to,UUID actor,Status status,long now)throws SQLException {
        authority(db,from,actor);pair(from,to);
        if(!List.of(Status.NEUTRAL,Status.PEACE,Status.ALLY).contains(status))throw new IllegalArgumentException("Предложить можно союз, мир или нейтралитет.");
        var current=relation(db,from,to);if(current.status()==status)throw new IllegalArgumentException("Эти отношения уже установлены.");
        expire(db,now);com.avrgaming.civcraft.modern.war.CapitulationStore.expire(db,now);if(incoming(db,to,now)!=null || com.avrgaming.civcraft.modern.war.CapitulationStore.incoming(db,to,now)!=null)throw new IllegalArgumentException("У цивилизации уже есть дипломатическое предложение.");
        long expires=Math.addExact(now,30000);
        try(var s=db.prepareStatement("INSERT INTO diplomacy_requests(from_civ,to_civ,status,before_status,expires_at) VALUES(?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
            s.setLong(1,from);s.setLong(2,to);s.setString(3,status.name());s.setString(4,current.status().name());s.setLong(5,expires);s.executeUpdate();
            try(var r=s.getGeneratedKeys()){if(!r.next())throw new SQLException("Diplomatic request ID missing");return new Request(r.getLong(1),from,to,status,current.status(),expires);}
        }
    }
    public static Request respond(Connection db,long to,UUID actor,boolean accept,long now)throws SQLException {
        authority(db,to,actor);expire(db,now);var request=incoming(db,to,now);
        if(request==null)throw new IllegalArgumentException("Нет действующего дипломатического предложения.");
        if(accept && relation(db,request.from(),to).status()!=request.before())throw new IllegalArgumentException("Отношения изменились после отправки предложения.");
        if(accept)change(db,request.from(),to,actor,request.status(),null,now);
        try(var s=db.prepareStatement("UPDATE diplomacy_requests SET state=? WHERE id=? AND state='PENDING'")) {
            s.setString(1,accept?"ACCEPTED":"DECLINED");s.setLong(2,request.id());if(s.executeUpdate()!=1)throw new SQLException("Diplomatic request changed before response");
        }
        return request;
    }
    public static Relation hostile(Connection db,long from,long to,UUID actor,long now)throws SQLException {
        authority(db,from,actor);pair(from,to);var before=relation(db,from,to);
        if(before.status()==Status.WAR)throw new IllegalArgumentException("Нельзя заменить войну враждебностью.");
        if(before.status()==Status.HOSTILE)throw new IllegalArgumentException("Враждебность уже объявлена.");
        change(db,from,to,actor,Status.HOSTILE,from,now);
        try(var s=db.prepareStatement("UPDATE diplomacy_requests SET state='CANCELLED' WHERE state='PENDING' AND ((from_civ=? AND to_civ=?) OR (from_civ=? AND to_civ=?))")) {
            s.setLong(1,from);s.setLong(2,to);s.setLong(3,to);s.setLong(4,from);s.executeUpdate();
        }
        return relation(db,from,to);
    }
    static void change(Connection db,long first,long second,UUID actor,Status status,Long aggressor,long now)throws SQLException {
        var before=relation(db,first,second);
        try(var s=db.prepareStatement("INSERT INTO diplomacy_relations VALUES(?,?,?,?,?) ON CONFLICT(first_civ,second_civ) DO UPDATE SET status=excluded.status,aggressor=excluded.aggressor,changed_at=excluded.changed_at")) {
            s.setLong(1,before.first());s.setLong(2,before.second());s.setString(3,status.name());if(aggressor==null)s.setNull(4,Types.INTEGER);else s.setLong(4,aggressor);s.setLong(5,now);s.executeUpdate();
        }
        try(var s=db.prepareStatement("INSERT INTO diplomacy_audit(first_civ,second_civ,before_status,after_status,actor_uuid,changed_at) VALUES(?,?,?,?,?,?)")) {
            s.setLong(1,before.first());s.setLong(2,before.second());s.setString(3,before.status().name());s.setString(4,status.name());s.setString(5,actor.toString());s.setLong(6,now);s.executeUpdate();
        }
    }
}
