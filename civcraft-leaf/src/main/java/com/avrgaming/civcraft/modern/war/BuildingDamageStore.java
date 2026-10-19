package com.avrgaming.civcraft.modern.war;
import com.avrgaming.civcraft.modern.diplomacy.DiplomacyStore;
import java.sql.*;
import java.util.UUID;
/** Persistent ordinary-building health. Physical destruction consumes DESTROY_PENDING later. */
public final class BuildingDamageStore {
    private BuildingDamageStore() {}
    public record Health(long build,String name,int hitpoints,int maximum,String phase) {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            String schema="CREATE TABLE IF NOT EXISTS building_combat_health(build_id INTEGER PRIMARY KEY REFERENCES structure_builds(id) ON DELETE CASCADE,hitpoints INTEGER NOT NULL CHECK(hitpoints>=0),maximum INTEGER NOT NULL CHECK(maximum>0),phase TEXT NOT NULL CHECK(phase IN ('DAMAGED','DESTROY_PENDING','DESTROYED')))";
            s.executeUpdate(schema);
            boolean migrated;try(var r=s.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='building_combat_health'")){r.next();String sql=r.getString(1);migrated=sql.contains("'DESTROYED'")&&!sql.contains("hitpoints<=maximum");}
            if(!migrated) {
                s.executeUpdate("ALTER TABLE building_combat_health RENAME TO building_health_before_ruins");s.executeUpdate(schema);
                s.executeUpdate("INSERT INTO building_combat_health SELECT * FROM building_health_before_ruins");s.executeUpdate("DROP TABLE building_health_before_ruins");
            }
            BuildingRuinStore.install(db);
            TowerHealthStore.install(db);
            s.executeUpdate("CREATE TABLE IF NOT EXISTS war_defeated_towns(war_start INTEGER NOT NULL REFERENCES war_sessions(start_at),town_id INTEGER NOT NULL REFERENCES towns(id),conqueror INTEGER NOT NULL REFERENCES civilizations(id),PRIMARY KEY(war_start,town_id))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS war_inactive_towns(war_start INTEGER NOT NULL REFERENCES war_sessions(start_at),town_id INTEGER NOT NULL REFERENCES towns(id),PRIMARY KEY(war_start,town_id))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS building_damage_hits(id TEXT PRIMARY KEY,build_id INTEGER NOT NULL,war_start INTEGER NOT NULL,actor_uuid TEXT NOT NULL,before_hp INTEGER NOT NULL,after_hp INTEGER NOT NULL,at INTEGER NOT NULL)");
        }
    }
    public static Health health(Connection db,long build)throws SQLException {
        try(var s=db.prepareStatement("SELECT b.display_name,COALESCE(h.hitpoints,b.max_hitpoints),COALESCE(h.maximum,b.max_hitpoints),COALESCE(h.phase,'DAMAGED') FROM structure_builds b LEFT JOIN building_combat_health h ON h.build_id=b.id WHERE b.id=?")) {
            s.setLong(1,build);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Building not found");return new Health(build,r.getString(1),r.getInt(2),TowerHealthStore.maximum(db,build,r.getInt(3)),r.getString(4));}
        }
    }
    public static Health hit(Connection db,String id,UUID actor,String world,int x,int y,int z,int amount,long now)throws SQLException {
        return hit(db,id,actor,world,x,y,z,amount,now,false);
    }
    public static Health cannonHit(Connection db,String id,UUID actor,String world,int x,int y,int z,int amount,long now)throws SQLException {
        return hit(db,id,actor,world,x,y,z,amount,now,true);
    }
    private static Health hit(Connection db,String id,UUID actor,String world,int x,int y,int z,int amount,long now,boolean cannon)throws SQLException {
        if(id==null || id.isBlank() || amount<1)throw new IllegalArgumentException("Неверный урон постройке");
        try(var s=db.prepareStatement("SELECT build_id FROM building_damage_hits WHERE id=?")) {
            s.setString(1,id);try(var r=s.executeQuery()){if(r.next())return health(db,r.getLong(1));}
        }
        long session;
        try(var s=db.prepareStatement("SELECT start_at FROM war_sessions WHERE state='ACTIVE' AND start_at<=? AND end_at>? ORDER BY start_at LIMIT 1")) {
            s.setLong(1,now);s.setLong(2,now);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Повреждать здания можно только во время боевого периода.");session=r.getLong(1);}
        }
        long town,civ;
        try(var s=db.prepareStatement("SELECT t.id,t.civ_id FROM residents r JOIN towns t ON t.id=r.town_id AND t.civ_id=r.civ_id WHERE r.uuid=?")) {
            s.setString(1,actor.toString());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Для участия в осаде нужно состоять в городе.");town=r.getLong(1);civ=r.getLong(2);}
        }
        try(var s=db.prepareStatement("SELECT 1 FROM war_defeated_towns WHERE war_start=? AND town_id=? UNION ALL SELECT 1 FROM war_inactive_towns WHERE war_start=? AND town_id=?")) {
            s.setLong(1,session);s.setLong(2,town);s.setLong(3,session);s.setLong(4,town);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Жители побеждённого города не могут атаковать здания.");}
        }
        long build,defender;String type,status;
        try(var s=db.prepareStatement("SELECT b.id,t.civ_id,b.structure_id,b.status FROM protected_blocks p JOIN structure_builds b ON b.id=p.build_id JOIN towns t ON t.id=b.town_id WHERE p.world=? AND p.x=? AND p.y=? AND p.z=?")) {
            s.setString(1,world);s.setInt(2,x);s.setInt(3,y);s.setInt(4,z);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Это не структурный блок города.");build=r.getLong(1);defender=r.getLong(2);type=r.getString(3);status=r.getString(4);}
        }
        if(DiplomacyStore.status(db,civ,defender)!=DiplomacyStore.Status.WAR)throw new IllegalArgumentException("Ваша цивилизация не воюет с владельцем здания.");
        if(!status.equals("COMPLETE"))throw new IllegalArgumentException("Здание не готово к атаке или уже разрушено.");
        if(type==null || type.equals("s_capitol") || type.equals("s_townhall") || !cannon && type.startsWith("w_"))throw new IllegalArgumentException("Для этого здания требуются отдельные военные правила.");
        var before=health(db,build);if(before.maximum()<1 || before.hitpoints()==0)throw new IllegalArgumentException("Это здание неуязвимо или уже разрушено.");
        int remaining=Math.max(0,before.hitpoints()-amount);String phase=remaining==0?"DESTROY_PENDING":"DAMAGED";
        try(var s=db.prepareStatement("INSERT INTO building_combat_health VALUES(?,?,?,?) ON CONFLICT(build_id) DO UPDATE SET hitpoints=excluded.hitpoints,maximum=excluded.maximum,phase=excluded.phase")) {
            s.setLong(1,build);s.setInt(2,remaining);s.setInt(3,before.maximum());s.setString(4,phase);s.executeUpdate();
        }
        if(remaining==0)try(var s=db.prepareStatement("UPDATE structure_builds SET status='WAR_DESTROY_PENDING' WHERE id=? AND status='COMPLETE'")) {s.setLong(1,build);if(s.executeUpdate()!=1)throw new SQLException("Building changed during destruction preparation");}
        try(var s=db.prepareStatement("INSERT INTO building_damage_hits VALUES(?,?,?,?,?,?,?)")) {
            s.setString(1,id);s.setLong(2,build);s.setLong(3,session);s.setString(4,actor.toString());s.setInt(5,before.hitpoints());s.setInt(6,remaining);s.setLong(7,now);s.executeUpdate();
        }
        return new Health(build,before.name(),remaining,before.maximum(),phase);
    }
}
