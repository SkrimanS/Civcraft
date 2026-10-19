package com.avrgaming.civcraft.modern.campnpc.quest;

import java.sql.*;
import java.util.UUID;

/** The inventory intent and quest progress share the core database and commit. */
public final class QuestSubmissionStore {
    private QuestSubmissionStore() {}
    public static void install(Connection db)throws SQLException {
        try(var statement=db.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS camp_quest_submissions(
                operation_id TEXT PRIMARY KEY REFERENCES goodie_chest_transfers(id),
                camp_id INTEGER NOT NULL,quest_id TEXT NOT NULL,player_uuid TEXT NOT NULL,
                before_amount INTEGER NOT NULL,after_amount INTEGER NOT NULL,target INTEGER NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('PREPARED','COMPLETE')))
                """);
            statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_pending_camp_quest_submission ON camp_quest_submissions(camp_id) WHERE status='PREPARED'");
            statement.executeUpdate("CREATE TRIGGER IF NOT EXISTS npc_submission_blocks_camp_delete BEFORE DELETE ON camps WHEN EXISTS(SELECT 1 FROM camp_quest_submissions WHERE camp_id=OLD.id AND status='PREPARED') BEGIN SELECT RAISE(ABORT,'Camp quest submission is pending'); END");
            statement.executeUpdate("CREATE TRIGGER IF NOT EXISTS npc_submission_blocks_owner_change BEFORE UPDATE OF owner_uuid ON camps WHEN NEW.owner_uuid<>OLD.owner_uuid AND EXISTS(SELECT 1 FROM camp_quest_submissions WHERE camp_id=OLD.id AND status='PREPARED') BEGIN SELECT RAISE(ABORT,'Camp quest submission is pending'); END");
        }
    }
    public static void prepare(Connection db,String operation,long camp,String quest,UUID player,int before,int removed,int target)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Quest submission requires a transaction");
        if(before<0 || removed<1 || target<1 || before>=target || removed>target-before || quest==null || quest.isBlank())throw new IllegalArgumentException("Invalid quest submission");
        com.avrgaming.civcraft.modern.camp.CampLonghouseStore.requireUnlocked(db,camp);
        try(var statement=db.prepareStatement("SELECT 1 FROM camps c WHERE c.id=? AND (c.owner_uuid=? OR EXISTS(SELECT 1 FROM camp_members m WHERE m.camp_id=c.id AND m.uuid=?))")) {
            statement.setLong(1,camp);statement.setString(2,player.toString());statement.setString(3,player.toString());try(var rows=statement.executeQuery()){if(!rows.next())throw new IllegalArgumentException("Вы больше не состоите в этом лагере.");}
        }
        try(var statement=db.prepareStatement("SELECT amount,completed,rewarded FROM camp_quest_progress WHERE camp_id=? AND quest_id=?")) {
            statement.setLong(1,camp);statement.setString(2,quest);try(var rows=statement.executeQuery()) {
                if(rows.next()){if(rows.getInt(1)!=before || rows.getBoolean(2) || rows.getBoolean(3))throw new IllegalArgumentException("Прогресс квеста уже изменился.");}
                else if(before!=0)throw new IllegalArgumentException("Прогресс квеста уже изменился.");
            }
        }
        try(var statement=db.prepareStatement("INSERT INTO camp_quest_submissions VALUES(?,?,?,?,?,?,?,'PREPARED')")) {
            statement.setString(1,operation);statement.setLong(2,camp);statement.setString(3,quest);statement.setString(4,player.toString());statement.setInt(5,before);statement.setInt(6,before+removed);statement.setInt(7,target);statement.executeUpdate();
        }
    }
    public static void complete(Connection db,String operation)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Quest completion requires a transaction");
        try(var statement=db.createStatement();var rows=statement.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='camp_quest_submissions'")){if(!rows.next())return;}
        long camp;String quest,player;int before,after,target;
        try(var statement=db.prepareStatement("SELECT * FROM camp_quest_submissions WHERE operation_id=? AND status='PREPARED'")) {
            statement.setString(1,operation);try(var rows=statement.executeQuery()) {
                if(!rows.next())return;camp=rows.getLong("camp_id");quest=rows.getString("quest_id");player=rows.getString("player_uuid");before=rows.getInt("before_amount");after=rows.getInt("after_amount");target=rows.getInt("target");
            }
        }
        try(var statement=db.prepareStatement("SELECT amount,completed,rewarded FROM camp_quest_progress WHERE camp_id=? AND quest_id=?")) {
            statement.setLong(1,camp);statement.setString(2,quest);try(var rows=statement.executeQuery()){if(rows.next()){if(rows.getInt(1)!=before || rows.getBoolean(2) || rows.getBoolean(3))throw new SQLException("Quest progress changed during inventory recovery");}else if(before!=0)throw new SQLException("Quest progress disappeared during inventory recovery");}
        }
        try(var statement=db.prepareStatement("INSERT INTO camp_quest_progress(camp_id,quest_id,amount,completed,rewarded,completer_uuid) VALUES(?,?,?,?,0,?) ON CONFLICT(camp_id,quest_id) DO UPDATE SET amount=excluded.amount,completed=excluded.completed,completer_uuid=excluded.completer_uuid")) {
            statement.setLong(1,camp);statement.setString(2,quest);statement.setInt(3,after);statement.setBoolean(4,after>=target);statement.setString(5,player);statement.executeUpdate();
        }
        try(var statement=db.prepareStatement("UPDATE camp_quest_submissions SET status='COMPLETE' WHERE operation_id=? AND status='PREPARED'")){statement.setString(1,operation);if(statement.executeUpdate()!=1)throw new SQLException("Quest inventory receipt changed");}
    }
}
