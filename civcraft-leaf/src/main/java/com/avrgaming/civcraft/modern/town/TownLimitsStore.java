package com.avrgaming.civcraft.modern.town;
import java.sql.*;
import java.util.*;

/** Original town land expansion; treasury and land share the caller's transaction. */
public final class TownLimitsStore {
    private TownLimitsStore() {}
    public static void install(Connection db)throws SQLException {
        boolean found=false;try(var s=db.createStatement();var r=s.executeQuery("PRAGMA table_info(town_claims)")){while(r.next())if(r.getString("name").equals("can_unclaim"))found=true;}
        if(!found)try(var s=db.createStatement()){s.executeUpdate("ALTER TABLE town_claims ADD COLUMN can_unclaim INTEGER NOT NULL DEFAULT 1");}
    }
    public static void seed(Connection db,long now)throws SQLException {
        record Seed(String world,int x,int z,long town,long civ,String mayor) {}
        var seeds=new ArrayList<Seed>();
        try(var s=db.createStatement();var r=s.executeQuery("""
            SELECT t.id,t.civ_id,t.mayor_uuid,t.world,t.x,t.z,sc.world,sc.chunk_x,sc.chunk_z
            FROM towns t LEFT JOIN structure_builds sb ON sb.town_id=t.id AND sb.structure_id IN ('s_capitol','s_townhall') AND sb.status IN ('QUEUED','BUILDING','COMPLETE')
            LEFT JOIN structure_chunks sc ON sc.build_id=sb.id ORDER BY t.id
            """)) {
            while(r.next())seeds.add(new Seed(r.getString(7)==null?r.getString(4):r.getString(7),r.getString(7)==null?Math.floorDiv(r.getInt(5),16):r.getInt(8),r.getString(7)==null?Math.floorDiv(r.getInt(6),16):r.getInt(9),r.getLong(1),r.getLong(2),r.getString(3)));
        }
        try(var insert=db.prepareStatement("INSERT OR IGNORE INTO town_claims(world,chunk_x,chunk_z,town_id,civ_id,claimed_by_uuid,claimed_at,can_unclaim) VALUES(?,?,?,?,?,?,?,0)");
            var protect=db.prepareStatement("UPDATE town_claims SET can_unclaim=0 WHERE world=? AND chunk_x=? AND chunk_z=? AND town_id=? AND can_unclaim<>0")) {
            for(var seed:seeds) {
                insert.setString(1,seed.world());insert.setInt(2,seed.x());insert.setInt(3,seed.z());insert.setLong(4,seed.town());insert.setLong(5,seed.civ());insert.setString(6,seed.mayor());insert.setLong(7,now);insert.executeUpdate();
                protect.setString(1,seed.world());protect.setInt(2,seed.x());protect.setInt(3,seed.z());protect.setLong(4,seed.town());protect.executeUpdate();
            }
        }
    }
    private record Town(long civ,int level,String world) {}
    private static Town manager(Connection db,long town,UUID actor)throws SQLException {
        try(var s=db.prepareStatement("""
            SELECT t.civ_id,t.level,t.world FROM towns t JOIN civilizations c ON c.id=t.civ_id
            JOIN residents r ON r.uuid=? AND r.town_id=t.id WHERE t.id=?
            AND (t.mayor_uuid=r.uuid OR c.leader_uuid=r.uuid OR EXISTS(SELECT 1 FROM town_assistants a WHERE a.town_id=t.id AND a.player_uuid=r.uuid))
            """)) {
            s.setString(1,actor.toString());s.setLong(2,town);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Управлять землёй города может мэр, помощник или лидер.");return new Town(r.getLong(1),r.getInt(2),r.getString(3));}
        }
    }
    public static long claim(Connection db,long town,UUID actor,PlotStore.Key key,TownCatalog catalog,int minimumDistance,long now)throws SQLException {
        var target=manager(db,town,actor);
        if(!target.world().equals(key.world()))throw new IllegalArgumentException("Участок должен быть в мире города.");
        try(var s=db.prepareStatement("SELECT 1 FROM town_claims WHERE world=? AND chunk_x=? AND chunk_z=?")){bind(s,key);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Этот чанк уже приватизирован.");}}
        try(var s=db.prepareStatement("SELECT 1 FROM culture_regions cr JOIN towns t ON t.id=cr.town_id WHERE cr.world=? AND cr.chunk_x=? AND cr.chunk_z=? AND t.civ_id=?")) {
            bind(s,key);s.setLong(4,target.civ());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Участок находится вне культуры вашей цивилизации.");}
        }
        try(var s=db.prepareStatement("SELECT 1 FROM town_claims WHERE world=? AND town_id=? AND ABS(chunk_x-?)+ABS(chunk_z-?)=1 LIMIT 1")) {
            s.setString(1,key.world());s.setLong(2,town);s.setInt(3,key.x());s.setInt(4,key.z());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Участок должен соседствовать с землёй вашего города.");}
        }
        int count;try(var s=db.prepareStatement("SELECT COUNT(*) FROM town_claims WHERE town_id=?")){s.setLong(1,town);try(var r=s.executeQuery()){r.next();count=r.getInt(1);}}
        if(count>=catalog.level(target.level()).plots())throw new IllegalArgumentException("Достигнут лимит участков уровня города.");
        try(var s=db.prepareStatement("SELECT chunk_x,chunk_z FROM town_claims WHERE world=? AND civ_id<>?")) {
            s.setString(1,key.world());s.setLong(2,target.civ());try(var r=s.executeQuery()){while(r.next())if(Math.hypot((double)key.x()-r.getInt(1),(double)key.z()-r.getInt(2))<=minimumDistance)throw new IllegalArgumentException("Слишком близко к земле другой цивилизации.");}
        }
        long cost=catalog.nextPlotCost(count);
        try(var s=db.prepareStatement("UPDATE towns SET coins=coins-? WHERE id=? AND coins>=?")){s.setLong(1,cost);s.setLong(2,town);s.setLong(3,cost);if(s.executeUpdate()!=1)throw new IllegalArgumentException("В казне города недостаточно монет: нужно "+cost+".");}
        try(var s=db.prepareStatement("INSERT INTO town_claims(world,chunk_x,chunk_z,town_id,civ_id,claimed_by_uuid,claimed_at) VALUES(?,?,?,?,?,?,?)")) {
            bind(s,key);s.setLong(4,town);s.setLong(5,target.civ());s.setString(6,actor.toString());s.setLong(7,now);s.executeUpdate();
        }
        return cost;
    }
    public static void unclaim(Connection db,long town,UUID actor,PlotStore.Key key)throws SQLException {
        manager(db,town,actor);
        try(var s=db.prepareStatement("DELETE FROM town_claims WHERE world=? AND chunk_x=? AND chunk_z=? AND town_id=? AND can_unclaim=1")) {
            bind(s,key);s.setLong(4,town);if(s.executeUpdate()!=1)throw new IllegalArgumentException("Участок чужой, отсутствует или защищён как земля ратуши.");
        }
    }
    private static void bind(PreparedStatement s,PlotStore.Key key)throws SQLException {s.setString(1,key.world());s.setInt(2,key.x());s.setInt(3,key.z());}
}
