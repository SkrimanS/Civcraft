package com.avrgaming.civcraft.modern.war;

import java.sql.*;

public final class HallValidationStore {
    private HallValidationStore() {}
    public record State(boolean validated,boolean valid,String reason,long checkedAt) {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS hall_validation(build_id INTEGER PRIMARY KEY REFERENCES structure_builds(id) ON DELETE CASCADE,foundation_valid INTEGER NOT NULL,reason TEXT NOT NULL,checked_at INTEGER NOT NULL)");}
    }
    public static void save(Connection db,long build,boolean valid,String reason,long now)throws SQLException {
        try(var s=db.prepareStatement("INSERT INTO hall_validation SELECT id,?,?,? FROM structure_builds WHERE id=? AND status='COMPLETE' ON CONFLICT(build_id) DO UPDATE SET foundation_valid=excluded.foundation_valid,reason=excluded.reason,checked_at=excluded.checked_at")) {
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
            try(var s=db.prepareStatement("SELECT COUNT(*) FROM towns t WHERE t.civ_id=? AND NOT EXISTS(SELECT 1 FROM structure_builds b WHERE b.town_id=t.id AND b.structure_id IN ('s_capitol','s_townhall'))")) {
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
}
