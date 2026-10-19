package com.avrgaming.civcraft.modern.war;

import java.sql.*;

public final class HallValidationStore {
    private HallValidationStore() {}
    public record State(boolean validated,boolean valid,String reason,long checkedAt) {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS hall_validation(build_id INTEGER PRIMARY KEY REFERENCES structure_builds(id) ON DELETE CASCADE,foundation_valid INTEGER NOT NULL,reason TEXT NOT NULL,checked_at INTEGER NOT NULL)");s.executeUpdate("CREATE TABLE IF NOT EXISTS structure_validation_geometry(build_id INTEGER PRIMARY KEY REFERENCES structure_builds(id) ON DELETE CASCADE,width INTEGER NOT NULL,depth INTEGER NOT NULL)");}
    }
    public static void save(Connection db,long build,boolean valid,String reason,long now)throws SQLException {
        save(db,build,valid,reason,now,false);
    }
    public static void saveManual(Connection db,long build,boolean valid,String reason,long now)throws SQLException {
        save(db,build,valid,reason,now,true);
    }
    private static void save(Connection db,long build,boolean valid,String reason,long now,boolean manual)throws SQLException {
        String statuses=manual?"('QUEUED','BUILDING','COMPLETE')":"('COMPLETE')";
        try(var s=db.prepareStatement("INSERT INTO hall_validation SELECT id,?,?,? FROM structure_builds WHERE id=? AND status IN "+statuses+" ON CONFLICT(build_id) DO UPDATE SET foundation_valid=excluded.foundation_valid,reason=excluded.reason,checked_at=excluded.checked_at")) {
            s.setBoolean(1,valid);s.setString(2,reason);s.setLong(3,now);s.setLong(4,build);s.executeUpdate();
        }
    }
    public static State state(Connection db,long build)throws SQLException {
        boolean validated=false,valid=true;String reason="";long checked=0,civ,town;Long capital,mother;String type;
        try(var s=db.prepareStatement("SELECT b.structure_id,b.town_id,t.civ_id,c.capital_town_id,tc.mother_civ,v.foundation_valid,v.reason,v.checked_at FROM structure_builds b JOIN towns t ON t.id=b.town_id JOIN civilizations c ON c.id=t.civ_id LEFT JOIN town_conquests tc ON tc.town_id=t.id LEFT JOIN hall_validation v ON v.build_id=b.id WHERE b.id=?")) {
            s.setLong(1,build);try(var r=s.executeQuery()) {
                if(!r.next())return new State(false,true,"",0);
                type=r.getString(1);town=r.getLong(2);civ=r.getLong(3);capital=r.getObject(4)==null?null:r.getLong(4);mother=r.getObject(5)==null?null:r.getLong(5);
                validated=r.getObject(6)!=null;if(validated){valid=r.getBoolean(6);reason=r.getString(7);checked=r.getLong(8);}
            }
        }
        if(type.equals("s_capitol")) {
            if(capital!=null&&capital!=town&&mother==null){valid=false;reason="Капитолий расположен вне действующей столицы.";}
            try(var s=db.prepareStatement("SELECT COUNT(*) FROM towns t WHERE t.civ_id=? AND NOT EXISTS(SELECT 1 FROM structure_builds b WHERE b.town_id=t.id AND b.structure_id IN ('s_capitol','s_townhall') AND b.status IN ('QUEUED','BUILDING','COMPLETE'))")) {
                s.setLong(1,civ);try(var r=s.executeQuery()){r.next();if(r.getInt(1)>0){valid=false;reason="В цивилизации есть город без ратуши.";}}
            }
        }
        return new State(validated,valid,reason,checked);
    }
    public static boolean capitalValid(Connection db,long civ)throws SQLException {
        try(var s=db.prepareStatement("SELECT b.id FROM structure_builds b JOIN civilizations c ON c.capital_town_id=b.town_id JOIN towns t ON t.id=b.town_id AND t.civ_id=c.id WHERE c.id=? AND b.structure_id='s_capitol' AND b.status IN ('QUEUED','BUILDING','COMPLETE') ORDER BY b.id LIMIT 1")) {
            s.setLong(1,civ);try(var r=s.executeQuery()){return r.next()&&state(db,r.getLong(1)).valid();}
        }
    }
    public static long nearestForManual(Connection db,java.util.UUID actor,String world,double x,double y,double z,long now)throws SQLException {
        if(WarClockStore.active(db,now))throw new IllegalArgumentException("Во время боя ручная валидация запрещена.");
        Long town=null;try(var s=db.prepareStatement("SELECT town_id FROM residents WHERE uuid=?")){s.setString(1,actor.toString());try(var r=s.executeQuery()){if(r.next()&&r.getObject(1)!=null)town=r.getLong(1);}}
        if(town==null)throw new IllegalArgumentException("Для проверки нужно состоять в городе.");
        try(var s=db.prepareStatement("SELECT b.id,b.town_id FROM structure_builds b LEFT JOIN structure_validation_geometry g ON g.build_id=b.id WHERE b.world=? AND b.town_id IS NOT NULL AND b.status IN ('QUEUED','BUILDING','COMPLETE') ORDER BY (b.x+COALESCE(g.width/2,0)-?)*(b.x+COALESCE(g.width/2,0)-?)+(b.y-?)*(b.y-?)+(b.z+COALESCE(g.depth/2,0)-?)*(b.z+COALESCE(g.depth/2,0)-?),b.id LIMIT 1")) {
            s.setString(1,world);s.setDouble(2,x);s.setDouble(3,x);s.setDouble(4,y);s.setDouble(5,y);s.setDouble(6,z);s.setDouble(7,z);
            try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Рядом нет здания для проверки.");if(r.getLong(2)!=town)throw new IllegalArgumentException("Можно проверить только ближайшее здание своего города.");return r.getLong(1);}
        }
    }
    public static void geometry(Connection db,long build,int width,int depth)throws SQLException {
        if(width<1||width>256||depth<1||depth>256)throw new IllegalArgumentException("Неверные размеры области проверки");
        try(var s=db.prepareStatement("INSERT INTO structure_validation_geometry VALUES(?,?,?) ON CONFLICT(build_id) DO UPDATE SET width=excluded.width,depth=excluded.depth")){s.setLong(1,build);s.setInt(2,width);s.setInt(3,depth);s.executeUpdate();}
    }
}
