package com.avrgaming.civcraft.modern.government;

import java.sql.*;
import java.util.UUID;

/** All mutations run inside the caller's storage transaction. */
public final class GovernmentStore {
    private GovernmentStore() {}
    public record Transition(long civId,String target,long startedAt) {}
    public static void install(Connection db)throws SQLException {
        try(var statement=db.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS government_transitions(
                      civ_id INTEGER PRIMARY KEY REFERENCES civilizations(id) ON DELETE CASCADE,
                      target TEXT NOT NULL, started_at INTEGER NOT NULL)
                    """);
        }
    }
    public static void begin(Connection db,long civId,UUID leader,GovernmentDefinition target,long now)throws SQLException {
        if(target.id().equals("gov_anarchy"))throw new IllegalArgumentException("Анархию нельзя выбрать как постоянное правительство.");
        try(var query=db.prepareStatement("SELECT leader_uuid,government FROM civilizations WHERE id=?")) {
            query.setLong(1,civId);
            try(var result=query.executeQuery()) {
                if(!result.next())throw new IllegalArgumentException("Цивилизация не найдена.");
                if(!leader.toString().equals(result.getString(1)))throw new IllegalArgumentException("Только лидер может сменить правительство.");
                String current=GovernmentDefinitions.normalize(result.getString(2));
                if(current.equals("gov_anarchy"))throw new IllegalArgumentException("Дождитесь окончания анархии.");
                if(current.equals(target.id()))throw new IllegalArgumentException("Это правительство уже действует.");
            }
        }
        if(!target.technology().isBlank()) {
            try(var query=db.prepareStatement("SELECT 1 FROM civ_technologies WHERE civ_id=? AND tech_id=?")) {
                query.setLong(1,civId);query.setString(2,target.technology());
                try(var result=query.executeQuery()){if(!result.next())throw new IllegalArgumentException("Требуется технология: "+target.technology());}
            }
        }
        try(var insert=db.prepareStatement("INSERT INTO government_transitions VALUES(?,?,?)")) {
            insert.setLong(1,civId);insert.setString(2,target.id());insert.setLong(3,now);insert.executeUpdate();
        }
        try(var update=db.prepareStatement("UPDATE civilizations SET government='gov_anarchy' WHERE id=?")) {
            update.setLong(1,civId);update.executeUpdate();
        }
        com.avrgaming.civcraft.modern.research.CivilizationScienceStore.cap(db,civId,0);
    }
    public static Transition transition(Connection db,long civId)throws SQLException {
        try(var query=db.prepareStatement("SELECT target,started_at FROM government_transitions WHERE civ_id=?")) {
            query.setLong(1,civId);
            try(var result=query.executeQuery()){return result.next()?new Transition(civId,result.getString(1),result.getLong(2)):null;}
        }
    }
    public static long durationMillis(double residents,double maximumHours) {
        return (long)(Math.max(0,Math.min(residents,maximumHours))*3_600_000L);
    }
    public static boolean finish(Connection db,Transition transition,long now,long duration)throws SQLException {
        if(now-transition.startedAt()<duration)return false;
        try(var remove=db.prepareStatement("DELETE FROM government_transitions WHERE civ_id=? AND target=? AND started_at=?")) {
            remove.setLong(1,transition.civId());remove.setString(2,transition.target());remove.setLong(3,transition.startedAt());
            if(remove.executeUpdate()!=1)return false;
        }
        try(var update=db.prepareStatement("UPDATE civilizations SET government=? WHERE id=?")) {
            update.setString(1,transition.target());update.setLong(2,transition.civId());update.executeUpdate();
        }
        return true;
    }
}
