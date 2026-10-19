package com.avrgaming.civcraft.modern.war;

import com.avrgaming.civcraft.modern.diplomacy.DiplomacyStore;
import com.avrgaming.civcraft.modern.trade.TradeStore;
import java.sql.*;
import java.util.*;

/** Cannon controls/ammunition/shot intent. World placement and impact workers own later phases. */
public final class SiegeCannonStore {
    private SiegeCannonStore() {}
    public record Draft(String id,UUID owner,long war,String world,SiegeCannonRules.Origin origin,SiegeCannonRules.Vector muzzle,int facing) {}
    public record Cannon(Draft draft,String state,int yaw,int pitch,int ammunition,int health,int maximum,long cooldownUntil) {}
    public record Shot(String id,String cannon,UUID actor,String world,SiegeCannonRules.Vector position,SiegeCannonRules.Vector direction,String state) {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS siege_cannons(id TEXT PRIMARY KEY,owner_uuid TEXT NOT NULL,war_start INTEGER NOT NULL,world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,rotation TEXT NOT NULL,muzzle_x REAL NOT NULL,muzzle_y REAL NOT NULL,muzzle_z REAL NOT NULL,facing INTEGER NOT NULL,state TEXT NOT NULL,yaw INTEGER NOT NULL DEFAULT 0,pitch INTEGER NOT NULL DEFAULT 0,ammunition INTEGER NOT NULL DEFAULT 0,health INTEGER NOT NULL,maximum INTEGER NOT NULL,cooldown_until INTEGER NOT NULL DEFAULT 0)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS siege_cannon_loads(id TEXT PRIMARY KEY,cannon_id TEXT NOT NULL REFERENCES siege_cannons(id),actor TEXT NOT NULL,before_ammo INTEGER NOT NULL,state TEXT NOT NULL DEFAULT 'PREPARED')");
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_pending_siege_load ON siege_cannon_loads(cannon_id) WHERE state='PREPARED'");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS siege_cannon_shots(id TEXT PRIMARY KEY,cannon_id TEXT NOT NULL REFERENCES siege_cannons(id),actor TEXT NOT NULL,world TEXT NOT NULL,x REAL NOT NULL,y REAL NOT NULL,z REAL NOT NULL,dx REAL NOT NULL,dy REAL NOT NULL,dz REAL NOT NULL,state TEXT NOT NULL DEFAULT 'QUEUED',created_at INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS siege_cannon_hits(id TEXT PRIMARY KEY,cannon_id TEXT NOT NULL REFERENCES siege_cannons(id),actor TEXT NOT NULL,before_hp INTEGER NOT NULL,after_hp INTEGER NOT NULL,at INTEGER NOT NULL)");
        }
    }
    private static void transaction(Connection db)throws SQLException{if(db.getAutoCommit())throw new SQLException("Siege cannon mutations require a transaction");}
    public static long currentWar(Connection db,long now)throws SQLException {
        try(var s=db.prepareStatement("SELECT start_at FROM war_sessions WHERE state='ACTIVE' AND start_at<=? AND end_at>? ORDER BY start_at LIMIT 1")){s.setLong(1,now);s.setLong(2,now);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Осадная пушка доступна только в боевой период.");return r.getLong(1);}}
    }
    private static Long civilization(Connection db,UUID player)throws SQLException {
        try(var s=db.prepareStatement("SELECT civ_id FROM residents WHERE uuid=?")){s.setString(1,player.toString());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Житель не найден.");return r.getObject(1)==null?null:r.getLong(1);}}
    }
    public static void register(Connection db,Draft draft,SiegeCannonCatalog catalog,long now)throws SQLException {
        transaction(db);if(currentWar(db,now)!=draft.war())throw new IllegalArgumentException("Боевой период пушки изменился.");
        if(civilization(db,draft.owner())==null)throw new IllegalArgumentException("Для размещения пушки нужно состоять в цивилизации.");
        SiegeCannonRules.direction(draft.facing(),0,0);
        try(var s=db.prepareStatement("INSERT INTO siege_cannons(id,owner_uuid,war_start,world,x,y,z,rotation,muzzle_x,muzzle_y,muzzle_z,facing,state,health,maximum) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'PREPARED',?,?)")) {
            s.setString(1,draft.id());s.setString(2,draft.owner().toString());s.setLong(3,draft.war());s.setString(4,draft.world());s.setInt(5,draft.origin().x());s.setInt(6,draft.origin().y());s.setInt(7,draft.origin().z());s.setString(8,draft.origin().direction());s.setDouble(9,draft.muzzle().x());s.setDouble(10,draft.muzzle().y());s.setDouble(11,draft.muzzle().z());s.setInt(12,draft.facing());s.setInt(13,catalog.health());s.setInt(14,catalog.health());s.executeUpdate();
        }
    }
    public static Cannon cannon(Connection db,String id)throws SQLException {
        try(var s=db.prepareStatement("SELECT * FROM siege_cannons WHERE id=?")){s.setString(1,id);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Пушка не найдена.");return new Cannon(new Draft(id,UUID.fromString(r.getString("owner_uuid")),r.getLong("war_start"),r.getString("world"),new SiegeCannonRules.Origin(r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getString("rotation")),new SiegeCannonRules.Vector(r.getDouble("muzzle_x"),r.getDouble("muzzle_y"),r.getDouble("muzzle_z")),r.getInt("facing")),r.getString("state"),r.getInt("yaw"),r.getInt("pitch"),r.getInt("ammunition"),r.getInt("health"),r.getInt("maximum"),r.getLong("cooldown_until"));}}
    }
    /** Call only after the placement worker has saved world and consumed-item snapshots. */
    public static void activate(Connection db,String id)throws SQLException {
        transaction(db);try(var s=db.prepareStatement("UPDATE siege_cannons SET state='ACTIVE' WHERE id=? AND state='PREPARED' AND EXISTS(SELECT 1 FROM war_sessions w WHERE w.start_at=siege_cannons.war_start AND w.state='ACTIVE' AND w.end_at>?)")){s.setString(1,id);s.setLong(2,System.currentTimeMillis());if(s.executeUpdate()!=1)throw new SQLException("Cannon placement is not prepared or its war has ended");}
    }
    private static void idle(Connection db,String id)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM siege_cannon_loads WHERE cannon_id=? AND state='PREPARED'")){s.setString(1,id);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Заряд пушки ещё сохраняется.");}}
    }
    private static Cannon use(Connection db,String id,UUID actor,long now)throws SQLException {
        var cannon=cannon(db,id);if(!cannon.state().equals("ACTIVE") || cannon.health()<=0)throw new IllegalArgumentException("Пушка уничтожена или недоступна.");
        if(currentWar(db,now)!=cannon.draft().war())throw new IllegalArgumentException("Боевой период этой пушки завершён.");
        if(!Objects.equals(civilization(db,actor),civilization(db,cannon.draft().owner())))throw new IllegalArgumentException("Управлять пушкой может только цивилизация её владельца.");
        idle(db,id);return cannon;
    }
    public static Cannon authorize(Connection db,String id,UUID actor,long now)throws SQLException{return use(db,id,actor,now);}
    public static Cannon adjust(Connection db,String id,UUID actor,boolean yaw,boolean right,long now)throws SQLException {
        transaction(db);var c=use(db,id,actor,now);int value=yaw?SiegeCannonRules.yaw(c.yaw(),right):SiegeCannonRules.pitch(c.pitch(),right);
        try(var s=db.prepareStatement("UPDATE siege_cannons SET "+(yaw?"yaw":"pitch")+"=? WHERE id=?")){s.setInt(1,value);s.setString(2,id);s.executeUpdate();}return cannon(db,id);
    }
    public static void reserveLoad(Connection db,String id,String receipt,UUID actor,byte[] afterInventory,SiegeCannonCatalog catalog,long now)throws SQLException {
        transaction(db);var c=use(db,id,actor,now);if(c.cooldownUntil()>now)throw new IllegalArgumentException("Пушка перезаряжается.");if(c.ammunition()>=catalog.tnt())throw new IllegalArgumentException("Пушка уже заряжена.");
        TradeStore.prepare(db,receipt,actor.toString(),afterInventory,0,0,null,0);
        try(var s=db.prepareStatement("INSERT INTO siege_cannon_loads(id,cannon_id,actor,before_ammo) VALUES(?,?,?,?)")){s.setString(1,receipt);s.setString(2,id);s.setString(3,actor.toString());s.setInt(4,c.ammunition());s.executeUpdate();}
    }
    public static boolean finishLoad(Connection db,String receipt)throws SQLException {
        transaction(db);String cannon;int before;
        try(var s=db.prepareStatement("SELECT l.cannon_id,l.before_ammo FROM siege_cannon_loads l JOIN trade_receipts t ON t.id=l.id AND t.player_uuid=l.actor WHERE l.id=? AND l.state='PREPARED' AND t.status='COMPLETE'")){s.setString(1,receipt);try(var r=s.executeQuery()){if(!r.next())return false;cannon=r.getString(1);before=r.getInt(2);}}
        try(var s=db.prepareStatement("UPDATE siege_cannons SET ammunition=ammunition+1 WHERE id=? AND ammunition=?")){s.setString(1,cannon);s.setInt(2,before);if(s.executeUpdate()!=1)throw new SQLException("Cannon ammunition changed during loading");}
        try(var s=db.prepareStatement("UPDATE siege_cannon_loads SET state='DONE' WHERE id=?")){s.setString(1,receipt);s.executeUpdate();}return true;
    }
    public static Shot fire(Connection db,String id,String shot,UUID actor,SiegeCannonCatalog catalog,long now)throws SQLException {
        transaction(db);var c=use(db,id,actor,now);if(c.cooldownUntil()>now)throw new IllegalArgumentException("Пушка перезаряжается.");if(c.ammunition()<catalog.tnt())throw new IllegalArgumentException("Для выстрела требуется "+catalog.tnt()+" TNT.");
        var direction=SiegeCannonRules.canonicalDirection(SiegeCannonRules.direction(c.draft().facing(),c.yaw(),c.pitch()));var position=c.draft().muzzle();
        try(var s=db.prepareStatement("INSERT INTO siege_cannon_shots(id,cannon_id,actor,world,x,y,z,dx,dy,dz,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)")){s.setString(1,shot);s.setString(2,id);s.setString(3,actor.toString());s.setString(4,c.draft().world());s.setDouble(5,position.x());s.setDouble(6,position.y());s.setDouble(7,position.z());s.setDouble(8,direction.x());s.setDouble(9,direction.y());s.setDouble(10,direction.z());s.setLong(11,now);s.executeUpdate();}
        try(var s=db.prepareStatement("UPDATE siege_cannons SET ammunition=0,cooldown_until=? WHERE id=?")){s.setLong(1,Math.addExact(now,catalog.cooldownSeconds()*1000L));s.setString(2,id);s.executeUpdate();}
        return new Shot(shot,id,actor,c.draft().world(),position,direction,"QUEUED");
    }
    public static int hit(Connection db,String id,String hit,UUID actor,long now)throws SQLException {
        transaction(db);try(var s=db.prepareStatement("SELECT after_hp FROM siege_cannon_hits WHERE id=? AND cannon_id=? AND actor=?")){s.setString(1,hit);s.setString(2,id);s.setString(3,actor.toString());try(var r=s.executeQuery()){if(r.next())return r.getInt(1);}}
        var c=cannon(db,id);idle(db,id);if(!c.state().equals("ACTIVE") || c.health()<=0)throw new IllegalArgumentException("Пушка уже уничтожена.");
        if(currentWar(db,now)!=c.draft().war())throw new IllegalArgumentException("Боевой период этой пушки завершён.");
        var attacker=civilization(db,actor);var owner=civilization(db,c.draft().owner());
        try(var s=db.prepareStatement("SELECT town_id FROM residents WHERE uuid=?")){s.setString(1,actor.toString());try(var r=s.executeQuery()){if(!r.next() || r.getObject(1)==null)throw new IllegalArgumentException("Для атаки пушки нужно состоять в городе.");}}
        if(attacker==null || owner==null || attacker.equals(owner) || DiplomacyStore.status(db,attacker,owner)!=DiplomacyStore.Status.WAR)throw new IllegalArgumentException("Можно повреждать только пушку вражеской цивилизации.");
        int health=c.health()-1;try(var s=db.prepareStatement("UPDATE siege_cannons SET health=?,state=? WHERE id=?")){s.setInt(1,health);s.setString(2,health==0?"DESTROY_PENDING":"ACTIVE");s.setString(3,id);s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO siege_cannon_hits VALUES(?,?,?,?,?,?)")){s.setString(1,hit);s.setString(2,id);s.setString(3,actor.toString());s.setInt(4,c.health());s.setInt(5,health);s.setLong(6,now);s.executeUpdate();}return health;
    }
    public static List<Cannon> cannons(Connection db)throws SQLException{
        var ids=new ArrayList<String>();try(var s=db.createStatement();var r=s.executeQuery("SELECT id FROM siege_cannons WHERE state IN ('PREPARED','ACTIVE','DESTROY_PENDING')")){while(r.next())ids.add(r.getString(1));}
        var result=new ArrayList<Cannon>();for(var id:ids)result.add(cannon(db,id));return result;
    }
    public static void finishLoads(Connection db)throws SQLException{
        var ids=new ArrayList<String>();try(var s=db.createStatement();var r=s.executeQuery("SELECT id FROM siege_cannon_loads WHERE state='PREPARED'")){while(r.next())ids.add(r.getString(1));}for(var id:ids)finishLoad(db,id);
    }
    public static List<Shot> shots(Connection db)throws SQLException{
        var result=new ArrayList<Shot>();try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM siege_cannon_shots WHERE state IN ('QUEUED','FLYING','IMPACT_PENDING') ORDER BY created_at,id")){
            while(r.next())result.add(new Shot(r.getString("id"),r.getString("cannon_id"),UUID.fromString(r.getString("actor")),r.getString("world"),new SiegeCannonRules.Vector(r.getDouble("x"),r.getDouble("y"),r.getDouble("z")),new SiegeCannonRules.Vector(r.getDouble("dx"),r.getDouble("dy"),r.getDouble("dz")),r.getString("state")));
        }return result;
    }
    public static void advance(Connection db,Shot shot,SiegeCannonRules.Step step,boolean impact)throws SQLException{
        transaction(db);try(var s=db.prepareStatement("UPDATE siege_cannon_shots SET x=?,y=?,z=?,dx=?,dy=?,dz=?,state=? WHERE id=? AND state IN ('QUEUED','FLYING')")){
            s.setDouble(1,step.position().x());s.setDouble(2,step.position().y());s.setDouble(3,step.position().z());s.setDouble(4,step.direction().x());s.setDouble(5,step.direction().y());s.setDouble(6,step.direction().z());s.setString(7,impact?"IMPACT_PENDING":"FLYING");s.setString(8,shot.id());if(s.executeUpdate()!=1)throw new SQLException("Cannon flight state changed");
        }
    }
}
