package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.util.UUID;
public final class OutlawStore {
    private OutlawStore() {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS town_outlaws(town_id INTEGER NOT NULL REFERENCES towns(id) ON DELETE CASCADE,uuid TEXT NOT NULL REFERENCES residents(uuid) ON DELETE CASCADE,declared_at INTEGER NOT NULL,armed_at INTEGER NOT NULL,notified INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(town_id,uuid))");}
    }
    public static void authority(Connection db,long town,UUID actor)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM towns t JOIN civilizations c ON c.id=t.civ_id JOIN residents r ON r.civ_id=c.id WHERE t.id=? AND r.uuid=? AND (t.mayor_uuid=r.uuid OR c.leader_uuid=r.uuid)")){s.setLong(1,town);s.setString(2,actor.toString());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Список преступников изменяет мэр или лидер цивилизации.");}}
    }
    public static void add(Connection db,long town,UUID actor,UUID target,long now)throws SQLException {
        authority(db,town,actor);
        try(var s=db.prepareStatement("SELECT town_id FROM residents WHERE uuid=?")){s.setString(1,target.toString());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Житель не найден.");if(r.getObject(1)!=null&&r.getLong(1)==town)throw new IllegalArgumentException("Нельзя объявить преступником жителя своего города.");}}
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO town_outlaws(town_id,uuid,declared_at,armed_at) VALUES(?,?,?,?)")){s.setLong(1,town);s.setString(2,target.toString());s.setLong(3,now);s.setLong(4,Math.addExact(now,1000));if(s.executeUpdate()!=1)throw new IllegalArgumentException("Житель уже объявлен преступником.");}
    }
    public static void remove(Connection db,long town,UUID actor,UUID target)throws SQLException {
        authority(db,town,actor);try(var s=db.prepareStatement("DELETE FROM town_outlaws WHERE town_id=? AND uuid=?")){s.setLong(1,town);s.setString(2,target.toString());if(s.executeUpdate()!=1)throw new IllegalArgumentException("Жителя нет в списке преступников.");}
    }
    public static boolean active(Connection db,long town,UUID player,long now)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM town_outlaws WHERE town_id=? AND uuid=? AND armed_at<=?")){s.setLong(1,town);s.setString(2,player.toString());s.setLong(3,now);try(var r=s.executeQuery()){return r.next();}}
    }
    public static void removeNewCivilizationMembers(Connection db,long town,long civ)throws SQLException {
        try(var s=db.prepareStatement("DELETE FROM town_outlaws WHERE town_id=? AND uuid IN (SELECT r.uuid FROM residents r JOIN towns t ON t.id=r.town_id AND t.civ_id=r.civ_id WHERE t.civ_id=?)")){s.setLong(1,town);s.setLong(2,civ);s.executeUpdate();}
    }
    public static int group(Connection db,long town,UUID actor,java.util.List<UUID> targets,boolean add,long now)throws SQLException {
        authority(db,town,actor);int count=0;
        try(var s=db.prepareStatement(add?"INSERT OR IGNORE INTO town_outlaws(town_id,uuid,declared_at,armed_at) VALUES(?,?,?,?)":"DELETE FROM town_outlaws WHERE town_id=? AND uuid=?")) {
            for(var target:targets){s.setLong(1,town);s.setString(2,target.toString());if(add){s.setLong(3,now);s.setLong(4,Math.addExact(now,1000));}count+=s.executeUpdate();}
        }return count;
    }
}
