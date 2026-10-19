package com.avrgaming.civcraft.modern.campnpc.reward;

import java.sql.*;
import java.util.*;

/** Immutable effects join the same commit as the physical inventory after-images. */
public final class QuestRewardStore {
    private QuestRewardStore() {}
    public record Effect(String kind,double amount,String value) {
        public Effect {
            if(!Double.isFinite(amount))throw new IllegalArgumentException("Некорректное количество в награде квеста.");
            if(!Set.of("MONEY","VAULT","XP","HP","UPGRADE","HEALTH","DAMAGE").contains(kind))throw new IllegalArgumentException("Unknown quest reward effect");
            if(kind.equals("VAULT") && (value==null || value.isBlank()))throw new IllegalArgumentException("Missing quest reward economy provider");
            if(kind.equals("XP") && (Math.round(amount)>Integer.MAX_VALUE || Math.round(amount)<Integer.MIN_VALUE))throw new IllegalArgumentException("Слишком большая награда опыта.");
            if(kind.equals("HP") && (Math.round(amount)>Integer.MAX_VALUE || Math.round(amount)<Integer.MIN_VALUE))throw new IllegalArgumentException("Слишком большая награда здоровья лагеря.");
            if(kind.equals("UPGRADE") && !Set.of("farm_upgrade","breaker_upgrade","longhouse_upgrade").contains(value))throw new IllegalArgumentException("Неизвестное улучшение лагеря.");
        }
    }
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS camp_quest_rewards(operation_id TEXT PRIMARY KEY REFERENCES goodie_chest_transfers(id),camp_id INTEGER NOT NULL,quest_id TEXT NOT NULL,leader_uuid TEXT NOT NULL,actor_uuid TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('PREPARED','COMPLETE')),UNIQUE(camp_id,quest_id))");
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_pending_camp_quest_reward ON camp_quest_rewards(camp_id) WHERE status='PREPARED'");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS camp_quest_reward_effects(operation_id TEXT NOT NULL REFERENCES camp_quest_rewards(operation_id),ordinal INTEGER NOT NULL,kind TEXT NOT NULL,amount REAL NOT NULL,value TEXT,PRIMARY KEY(operation_id,ordinal))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS camp_quest_reward_commands(operation_id TEXT NOT NULL REFERENCES camp_quest_rewards(operation_id),ordinal INTEGER NOT NULL,command TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'WAITING' CHECK(status IN ('WAITING','CLAIMED','DONE','FAILED')),PRIMARY KEY(operation_id,ordinal))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS camp_quest_reward_payments(operation_id TEXT NOT NULL REFERENCES camp_quest_rewards(operation_id),ordinal INTEGER NOT NULL,leader_uuid TEXT NOT NULL,amount INTEGER NOT NULL,provider TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'WAITING' CHECK(status IN ('WAITING','CLAIMED','DONE','FAILED')),PRIMARY KEY(operation_id,ordinal))");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS npc_reward_blocks_camp_delete BEFORE DELETE ON camps WHEN EXISTS(SELECT 1 FROM camp_quest_rewards WHERE camp_id=OLD.id AND status='PREPARED') BEGIN SELECT RAISE(ABORT,'Camp quest reward is pending'); END");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS npc_reward_blocks_owner_change BEFORE UPDATE OF owner_uuid ON camps WHEN NEW.owner_uuid<>OLD.owner_uuid AND EXISTS(SELECT 1 FROM camp_quest_rewards WHERE camp_id=OLD.id AND status='PREPARED') BEGIN SELECT RAISE(ABORT,'Camp quest reward is pending'); END");
        }
    }
    public static boolean exists(Connection db,long camp,String quest)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM camp_quest_rewards WHERE camp_id=? AND quest_id=?")){s.setLong(1,camp);s.setString(2,quest);try(var r=s.executeQuery()){return r.next();}}
    }
    public static void prepare(Connection db,String operation,long camp,String quest,UUID leader,UUID actor,List<Effect> effects,List<String> commands)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Quest rewards require a transaction");
        com.avrgaming.civcraft.modern.camp.CampLonghouseStore.requireUnlocked(db,camp);
        try(var s=db.prepareStatement("SELECT 1 FROM camps c JOIN camp_quest_progress p ON p.camp_id=c.id WHERE c.id=? AND c.owner_uuid=? AND p.quest_id=? AND p.completed=1 AND p.rewarded=0")) {
            s.setLong(1,camp);s.setString(2,leader.toString());s.setString(3,quest);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Лагерь или награда квеста уже изменились.");}
        }
        try(var s=db.prepareStatement("INSERT INTO camp_quest_rewards VALUES(?,?,?,?,?,'PREPARED')")){s.setString(1,operation);s.setLong(2,camp);s.setString(3,quest);s.setString(4,leader.toString());s.setString(5,actor.toString());s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO camp_quest_reward_effects VALUES(?,?,?,?,?)")) {
            int i=0;for(var effect:effects){s.setString(1,operation);s.setInt(2,i++);s.setString(3,effect.kind());s.setDouble(4,effect.amount());s.setString(5,effect.value());s.addBatch();}s.executeBatch();
        }
        try(var s=db.prepareStatement("INSERT INTO camp_quest_reward_commands(operation_id,ordinal,command) VALUES(?,?,?)")) {
            int i=0;for(var command:commands){if(command==null || command.isBlank() || command.length()>32768)throw new IllegalArgumentException("Некорректная команда награды.");s.setString(1,operation);s.setInt(2,i++);s.setString(3,command);s.addBatch();}s.executeBatch();
        }
    }
    public static void complete(Connection db,String operation)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Quest rewards require a transaction");
        try(var s=db.createStatement();var r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='camp_quest_rewards'")){if(!r.next())return;}
        long camp;String quest,leader;
        try(var s=db.prepareStatement("SELECT camp_id,quest_id,leader_uuid FROM camp_quest_rewards WHERE operation_id=? AND status='PREPARED'")) {
            s.setString(1,operation);try(var r=s.executeQuery()){if(!r.next())return;camp=r.getLong(1);quest=r.getString(2);leader=r.getString(3);}
        }
        var effects=new ArrayList<Effect>();
        try(var s=db.prepareStatement("SELECT kind,amount,value FROM camp_quest_reward_effects WHERE operation_id=? ORDER BY ordinal")){s.setString(1,operation);try(var r=s.executeQuery()){while(r.next())effects.add(new Effect(r.getString(1),r.getDouble(2),r.getString(3)));}}
        for(int i=0;i<effects.size();i++)apply(db,operation,i,camp,leader,effects.get(i));
        try(var s=db.prepareStatement("UPDATE camp_quest_progress SET rewarded=1 WHERE camp_id=? AND quest_id=? AND completed=1 AND rewarded=0")){s.setLong(1,camp);s.setString(2,quest);if(s.executeUpdate()!=1)throw new SQLException("Quest reward progress changed during recovery");}
        try(var s=db.prepareStatement("UPDATE camp_quest_rewards SET status='COMPLETE' WHERE operation_id=? AND status='PREPARED'")){s.setString(1,operation);if(s.executeUpdate()!=1)throw new SQLException("Quest reward receipt changed during recovery");}
    }
    private static void apply(Connection db,String operation,int ordinal,long camp,String leader,Effect effect)throws SQLException {
        long amount=Math.round(effect.amount());
        switch(effect.kind()) {
            case "MONEY" -> {if(amount>0)try(var s=db.prepareStatement("UPDATE residents SET coins=coins+?,updated_at=? WHERE uuid=? AND coins<=?")){s.setLong(1,amount);s.setLong(2,System.currentTimeMillis());s.setString(3,leader);s.setLong(4,Long.MAX_VALUE-amount);if(s.executeUpdate()!=1)throw new SQLException("Camp reward balance cannot be credited");}}
            case "VAULT" -> {if(amount>0)try(var s=db.prepareStatement("INSERT INTO camp_quest_reward_payments(operation_id,ordinal,leader_uuid,amount,provider) VALUES(?,?,?,?,?)")){s.setString(1,operation);s.setInt(2,ordinal);s.setString(3,leader);s.setLong(4,amount);s.setString(5,effect.value());s.executeUpdate();}}
            case "XP" -> {if(amount>0)try(var s=db.prepareStatement("INSERT INTO camp_npc_progress(camp_id,experience) VALUES(?,?) ON CONFLICT(camp_id) DO UPDATE SET experience=MIN(2147483647,camp_npc_progress.experience+excluded.experience)")){s.setLong(1,camp);s.setLong(2,amount);s.executeUpdate();}}
            case "HP" -> {if(amount!=0)try(var s=db.prepareStatement("UPDATE camps SET control_hp=MAX(1,MIN(2147483647,control_hp+?)),control_max_hp=MAX(1,MIN(2147483647,control_max_hp+?)) WHERE id=? AND control_hp>0")){s.setLong(1,amount);s.setLong(2,amount);s.setLong(3,camp);s.executeUpdate();}}
            case "UPGRADE" -> {try(var s=db.prepareStatement("UPDATE camps SET "+effect.value()+"=1 WHERE id=?")){s.setLong(1,camp);if(s.executeUpdate()!=1)throw new SQLException("Camp reward owner disappeared");}}
            case "HEALTH","DAMAGE" -> {
                double health=0,damage=0;
                try(var s=db.prepareStatement("SELECT health_bonus,damage_bonus FROM camp_npc_bonuses WHERE camp_id=?")){s.setLong(1,camp);try(var r=s.executeQuery()){if(r.next()){health=r.getDouble(1);damage=r.getDouble(2);}}}
                if(effect.kind().equals("HEALTH"))health+=effect.amount();else damage+=effect.amount();
                if(!Double.isFinite(health)||!Double.isFinite(damage))throw new SQLException("Camp reward attribute overflow");
                try(var s=db.prepareStatement("INSERT INTO camp_npc_bonuses(camp_id,health_bonus,damage_bonus) VALUES(?,?,?) ON CONFLICT(camp_id) DO UPDATE SET health_bonus=excluded.health_bonus,damage_bonus=excluded.damage_bonus")){s.setLong(1,camp);s.setDouble(2,health);s.setDouble(3,damage);s.executeUpdate();}
            }
            default -> throw new SQLException("Unknown stored quest reward effect");
        }
    }
    public record Command(String operation,int ordinal,String command){}
    public static List<Command> waitingCommands(Connection db)throws SQLException {
        var out=new ArrayList<Command>();try(var s=db.createStatement();var r=s.executeQuery("SELECT c.operation_id,c.ordinal,c.command FROM camp_quest_reward_commands c JOIN camp_quest_rewards job ON job.operation_id=c.operation_id WHERE c.status='WAITING' AND job.status='COMPLETE' ORDER BY c.operation_id,c.ordinal")){while(r.next())out.add(new Command(r.getString(1),r.getInt(2),r.getString(3)));}return out;
    }
    /** External commands cannot participate in SQLite: claim before dispatch and retain ambiguity. */
    public static boolean claimCommand(Connection db,Command command)throws SQLException {
        try(var s=db.prepareStatement("UPDATE camp_quest_reward_commands SET status='CLAIMED' WHERE operation_id=? AND ordinal=? AND status='WAITING'")){s.setString(1,command.operation());s.setInt(2,command.ordinal());return s.executeUpdate()==1;}
    }
    public static void finishCommand(Connection db,Command command,boolean success)throws SQLException {
        try(var s=db.prepareStatement("UPDATE camp_quest_reward_commands SET status=? WHERE operation_id=? AND ordinal=? AND status='CLAIMED'")){s.setString(1,success?"DONE":"FAILED");s.setString(2,command.operation());s.setInt(3,command.ordinal());s.executeUpdate();}
    }
    public record Payment(String operation,int ordinal,UUID leader,long amount,String provider){}
    public static List<Payment> waitingPayments(Connection db)throws SQLException {
        var out=new ArrayList<Payment>();try(var s=db.createStatement();var r=s.executeQuery("SELECT p.operation_id,p.ordinal,p.leader_uuid,p.amount,p.provider FROM camp_quest_reward_payments p JOIN camp_quest_rewards job ON job.operation_id=p.operation_id WHERE p.status='WAITING' AND job.status='COMPLETE' ORDER BY p.operation_id,p.ordinal")){while(r.next())out.add(new Payment(r.getString(1),r.getInt(2),UUID.fromString(r.getString(3)),r.getLong(4),r.getString(5)));}return out;
    }
    public static boolean claimPayment(Connection db,Payment payment)throws SQLException {
        try(var s=db.prepareStatement("UPDATE camp_quest_reward_payments SET status='CLAIMED' WHERE operation_id=? AND ordinal=? AND status='WAITING'")){s.setString(1,payment.operation());s.setInt(2,payment.ordinal());return s.executeUpdate()==1;}
    }
    public static void finishPayment(Connection db,Payment payment,boolean success)throws SQLException {
        try(var s=db.prepareStatement("UPDATE camp_quest_reward_payments SET status=? WHERE operation_id=? AND ordinal=? AND status='CLAIMED'")){s.setString(1,success?"DONE":"FAILED");s.setString(2,payment.operation());s.setInt(3,payment.ordinal());s.executeUpdate();}
    }
}
