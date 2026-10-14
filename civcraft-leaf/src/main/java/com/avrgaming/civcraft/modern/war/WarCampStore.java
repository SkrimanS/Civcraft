package com.avrgaming.civcraft.modern.war;

import java.sql.*;
import java.util.*;

/** Persistent temporary camps; ownership is fixed independently of later town conquest. */
public final class WarCampStore {
    private WarCampStore() {}
    public record Authority(long town,long civilization,long war) {}
    public record Camp(long id,long war,long civilization,long town,String world,int x,int y,int z,String state) {}
    public record Block(String world,int x,int y,int z,String before,String after,String beforeNbt) {
        public Block(String world,int x,int y,int z,String before,String after){this(world,x,y,z,before,after,null);}
    }
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS war_camps(id INTEGER PRIMARY KEY AUTOINCREMENT,request_id TEXT NOT NULL UNIQUE,war_start INTEGER NOT NULL REFERENCES war_sessions(start_at),civ_id INTEGER NOT NULL,town_id INTEGER NOT NULL,actor TEXT NOT NULL,world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,state TEXT NOT NULL CHECK(state IN ('PREPARED','ACTIVE','DESTROYED','RESTORING','RESTORED')),created_at INTEGER NOT NULL,changed_at INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS war_camp_blocks(camp_id INTEGER NOT NULL REFERENCES war_camps(id) ON DELETE CASCADE,world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,before_data TEXT NOT NULL,after_data TEXT NOT NULL,PRIMARY KEY(camp_id,world,x,y,z))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS war_camp_cooldowns(civ_id INTEGER PRIMARY KEY REFERENCES civilizations(id) ON DELETE CASCADE,built_at INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS war_camp_points(camp_id INTEGER NOT NULL REFERENCES war_camps(id) ON DELETE CASCADE,kind TEXT NOT NULL CHECK(kind IN ('respawn','control')),world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,maximum INTEGER NOT NULL,hitpoints INTEGER NOT NULL,PRIMARY KEY(camp_id,kind,world,x,y,z))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS war_camp_hits(id TEXT PRIMARY KEY,camp_id INTEGER NOT NULL REFERENCES war_camps(id),actor TEXT NOT NULL,before_hp INTEGER NOT NULL,after_hp INTEGER NOT NULL,at INTEGER NOT NULL)");
            boolean nbt=false;try(var r=s.executeQuery("PRAGMA table_info(war_camp_blocks)")){while(r.next())if(r.getString("name").equals("before_nbt"))nbt=true;}if(!nbt)s.executeUpdate("ALTER TABLE war_camp_blocks ADD COLUMN before_nbt TEXT");
        }
    }
    public static Authority authority(Connection db,UUID actor,long now)throws SQLException {
        long town,civ;
        try(var s=db.prepareStatement("SELECT t.id,c.id,c.leader_uuid,EXISTS(SELECT 1 FROM civ_advisors a WHERE a.civ_id=c.id AND a.resident_uuid=r.uuid) FROM residents r JOIN towns t ON t.id=r.town_id AND t.civ_id=r.civ_id JOIN civilizations c ON c.id=t.civ_id WHERE r.uuid=?")) {
            s.setString(1,actor.toString());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Для военного лагеря нужно состоять в городе.");town=r.getLong(1);civ=r.getLong(2);if(!actor.toString().equals(r.getString(3))&&!r.getBoolean(4))throw new IllegalArgumentException("Военный лагерь основывает лидер или советник цивилизации.");}
        }
        try(var s=db.prepareStatement("SELECT start_at FROM war_sessions WHERE state='ACTIVE' AND start_at<=? AND end_at>? ORDER BY start_at LIMIT 1")){s.setLong(1,now);s.setLong(2,now);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Военный лагерь создают только в боевой период.");return new Authority(town,civ,r.getLong(1));}}
    }
    public static int cooldownMinutes(Long builtAt,int timeoutMinutes,long now) {
        if(timeoutMinutes<0)throw new IllegalArgumentException("Negative camp rebuild timeout");
        if(builtAt==null)return 0;
        long deadline=Math.addExact(builtAt,Math.multiplyExact((long)timeoutMinutes,60000));
        // The source truncates to whole minutes and permits rebuilding in the last minute.
        return now>deadline?0:Math.toIntExact((deadline-now)/60000);
    }
    public static Camp prepare(Connection db,String request,UUID actor,String world,int x,int y,int z,List<Block> blocks,int maximum,int timeout,long now)throws SQLException {
        try(var s=db.prepareStatement("SELECT id FROM war_camps WHERE request_id=?")){s.setString(1,request);try(var r=s.executeQuery()){if(r.next())return camp(db,r.getLong(1));}}
        if(request==null||request.isBlank()||maximum<1||blocks.isEmpty())throw new IllegalArgumentException("Invalid camp preparation");
        var authority=authority(db,actor,now);
        try(var s=db.prepareStatement("SELECT COUNT(*) FROM war_camps WHERE civ_id=? AND war_start=? AND state IN ('PREPARED','ACTIVE')")){s.setLong(1,authority.civilization());s.setLong(2,authority.war());try(var r=s.executeQuery()){r.next();if(r.getInt(1)>=maximum)throw new IllegalArgumentException("Достигнут предел военных лагерей: "+maximum+".");}}
        try(var s=db.prepareStatement("SELECT built_at FROM war_camp_cooldowns WHERE civ_id=?")){s.setLong(1,authority.civilization());try(var r=s.executeQuery()){int left=cooldownMinutes(r.next()?r.getLong(1):null,timeout,now);if(left>0)throw new IllegalArgumentException("До нового военного лагеря: "+left+" мин.");}}
        try(var s=db.prepareStatement("SELECT 1 FROM war_camp_blocks b JOIN war_camps c ON c.id=b.camp_id WHERE b.world=? AND b.x=? AND b.y=? AND b.z=? AND c.state IN ('PREPARED','ACTIVE','RESTORING') LIMIT 1")) {
            for(var block:blocks){s.setString(1,block.world());s.setInt(2,block.x());s.setInt(3,block.y());s.setInt(4,block.z());try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Здесь уже размещён или восстанавливается военный лагерь.");}}
        }
        long id;try(var s=db.prepareStatement("INSERT INTO war_camps(request_id,war_start,civ_id,town_id,actor,world,x,y,z,state,created_at,changed_at) VALUES(?,?,?,?,?,?,?,?,?,'PREPARED',?,?)",Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1,request);s.setLong(2,authority.war());s.setLong(3,authority.civilization());s.setLong(4,authority.town());s.setString(5,actor.toString());s.setString(6,world);s.setInt(7,x);s.setInt(8,y);s.setInt(9,z);s.setLong(10,now);s.setLong(11,now);s.executeUpdate();try(var r=s.getGeneratedKeys()){r.next();id=r.getLong(1);}
        }
        try(var s=db.prepareStatement("INSERT INTO war_camp_blocks(camp_id,world,x,y,z,before_data,after_data,before_nbt) VALUES(?,?,?,?,?,?,?,?)")){for(var block:blocks){s.setLong(1,id);s.setString(2,block.world());s.setInt(3,block.x());s.setInt(4,block.y());s.setInt(5,block.z());s.setString(6,block.before());s.setString(7,block.after());s.setString(8,block.beforeNbt());s.addBatch();}s.executeBatch();}
        return camp(db,id);
    }
    public static Camp camp(Connection db,long id)throws SQLException {
        try(var s=db.prepareStatement("SELECT * FROM war_camps WHERE id=?")){s.setLong(1,id);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Camp not found");return new Camp(id,r.getLong("war_start"),r.getLong("civ_id"),r.getLong("town_id"),r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getString("state"));}}
    }
    public static void activate(Connection db,long id,long now)throws SQLException {
        var camp=camp(db,id);if(camp.state().equals("ACTIVE"))return;
        if(!camp.state().equals("PREPARED"))throw new IllegalArgumentException("Лагерь уже завершён или уничтожен.");
        try(var s=db.prepareStatement("SELECT 1 FROM war_sessions WHERE start_at=? AND state='ACTIVE' AND start_at<=? AND end_at>?")){s.setLong(1,camp.war());s.setLong(2,now);s.setLong(3,now);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Бой закончился до завершения лагеря.");}}
        transition(db,id,"ACTIVE",now);stamp(db,camp.civilization(),now);
    }
    public static void destroyed(Connection db,long id,long now)throws SQLException {
        var camp=camp(db,id);if(camp.state().equals("DESTROYED"))return;if(!camp.state().equals("ACTIVE"))throw new IllegalArgumentException("Этот лагерь недоступен для атаки.");transition(db,id,"DESTROYED",now);stamp(db,camp.civilization(),now);
    }
    private static void stamp(Connection db,long civ,long now)throws SQLException {
        try(var s=db.prepareStatement("INSERT INTO war_camp_cooldowns VALUES(?,?) ON CONFLICT(civ_id) DO UPDATE SET built_at=excluded.built_at")){s.setLong(1,civ);s.setLong(2,now);s.executeUpdate();}
    }
    private static void transition(Connection db,long id,String state,long now)throws SQLException {
        try(var s=db.prepareStatement("UPDATE war_camps SET state=?,changed_at=? WHERE id=?")){s.setString(1,state);s.setLong(2,now);s.setLong(3,id);s.executeUpdate();}
    }
    public static void scheduleRestoration(Connection db,long now)throws SQLException {
        try(var s=db.prepareStatement("UPDATE war_camps SET state='RESTORING',changed_at=? WHERE state IN ('PREPARED','ACTIVE','DESTROYED') AND EXISTS(SELECT 1 FROM war_sessions w WHERE w.start_at=war_camps.war_start AND (w.state<>'ACTIVE' OR w.end_at<=?))")){s.setLong(1,now);s.setLong(2,now);s.executeUpdate();}
    }
    public static Long nextRestoration(Connection db)throws SQLException {
        // Later overlapping camps are undone first, so the earliest terrain snapshot wins.
        try(var s=db.createStatement();var r=s.executeQuery("SELECT id FROM war_camps WHERE state='RESTORING' ORDER BY id DESC LIMIT 1")){return r.next()?r.getLong(1):null;}
    }
    public static List<Block> blocks(Connection db,long id)throws SQLException {
        var out=new ArrayList<Block>();try(var s=db.prepareStatement("SELECT * FROM war_camp_blocks WHERE camp_id=? ORDER BY world,x,y,z")){s.setLong(1,id);try(var r=s.executeQuery()){while(r.next())out.add(new Block(r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getString("before_data"),r.getString("after_data"),r.getString("before_nbt")));}}return List.copyOf(out);
    }
    public static void restored(Connection db,long id,long now)throws SQLException {
        var camp=camp(db,id);if(camp.state().equals("RESTORED"))return;if(!camp.state().equals("RESTORING"))throw new IllegalArgumentException("Лагерь не ожидает восстановления территории.");
        Long next=nextRestoration(db);if(next==null||next!=id)throw new IllegalArgumentException("Сначала нужно восстановить более поздний лагерь.");transition(db,id,"RESTORED",now);
    }
    public static void point(Connection db,long camp,String kind,String world,int x,int y,int z,int maximum)throws SQLException {
        if(!Set.of("control","respawn").contains(kind)||maximum<0||(kind.equals("control")&&maximum==0))throw new IllegalArgumentException("Invalid camp point");
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO war_camp_points VALUES(?,?,?,?,?,?,?,?)")){s.setLong(1,camp);s.setString(2,kind);s.setString(3,world);s.setInt(4,x);s.setInt(5,y);s.setInt(6,z);s.setInt(7,maximum);s.setInt(8,maximum);s.executeUpdate();}
    }
    public record Hit(long camp,int health,boolean destroyed) {}
    public static Hit hit(Connection db,String request,UUID actor,long camp,String world,int x,int y,int z,int amount,long now)throws SQLException {
        try(var s=db.prepareStatement("SELECT camp_id,after_hp FROM war_camp_hits WHERE id=?")){s.setString(1,request);try(var r=s.executeQuery()){if(r.next())return new Hit(r.getLong(1),r.getInt(2),false);}}
        if(request==null||request.isBlank()||amount<1)throw new IllegalArgumentException("Invalid camp hit");
        var current=camp(db,camp);if(!current.state().equals("ACTIVE"))throw new IllegalArgumentException("Этот лагерь недоступен для атаки.");
        try(var s=db.prepareStatement("SELECT 1 FROM war_sessions WHERE start_at=? AND state='ACTIVE' AND start_at<=? AND end_at>?")){s.setLong(1,current.war());s.setLong(2,now);s.setLong(3,now);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Лагерь атакуют только в его боевой период.");}}
        long town,civ;try(var s=db.prepareStatement("SELECT t.id,t.civ_id FROM residents r JOIN towns t ON t.id=r.town_id AND t.civ_id=r.civ_id WHERE r.uuid=?")){s.setString(1,actor.toString());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Для атаки лагеря нужно состоять в городе.");town=r.getLong(1);civ=r.getLong(2);}}
        try(var s=db.prepareStatement("SELECT 1 FROM war_defeated_towns WHERE war_start=? AND town_id=? UNION ALL SELECT 1 FROM war_inactive_towns WHERE war_start=? AND town_id=?")){s.setLong(1,current.war());s.setLong(2,town);s.setLong(3,current.war());s.setLong(4,town);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Ваш город уже побеждён в этой войне.");}}
        if(com.avrgaming.civcraft.modern.diplomacy.DiplomacyStore.status(db,civ,current.civilization())!=com.avrgaming.civcraft.modern.diplomacy.DiplomacyStore.Status.WAR)throw new IllegalArgumentException("Вы не воюете с владельцем лагеря.");
        int before;try(var s=db.prepareStatement("SELECT hitpoints FROM war_camp_points WHERE camp_id=? AND kind='control' AND world=? AND x=? AND y=? AND z=?")){s.setLong(1,camp);s.setString(2,world);s.setInt(3,x);s.setInt(4,y);s.setInt(5,z);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Нужно атаковать контрольный блок лагеря.");before=r.getInt(1);}}
        if(before==0)throw new IllegalArgumentException("Контрольный блок лагеря уже уничтожен.");int after=Math.max(0,before-amount);
        try(var s=db.prepareStatement("UPDATE war_camp_points SET hitpoints=? WHERE camp_id=? AND kind='control' AND world=? AND x=? AND y=? AND z=?")){s.setInt(1,after);s.setLong(2,camp);s.setString(3,world);s.setInt(4,x);s.setInt(5,y);s.setInt(6,z);s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO war_camp_hits VALUES(?,?,?,?,?,?)")){s.setString(1,request);s.setLong(2,camp);s.setString(3,actor.toString());s.setInt(4,before);s.setInt(5,after);s.setLong(6,now);s.executeUpdate();}
        boolean destroyed;try(var s=db.prepareStatement("SELECT COUNT(*) FROM war_camp_points WHERE camp_id=? AND kind='control' AND hitpoints>0")){s.setLong(1,camp);try(var r=s.executeQuery()){r.next();destroyed=r.getInt(1)==0;}}
        if(destroyed)destroyed(db,camp,now);return new Hit(camp,after,destroyed);
    }
}
