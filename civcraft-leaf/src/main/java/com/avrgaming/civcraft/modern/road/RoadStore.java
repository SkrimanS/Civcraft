package com.avrgaming.civcraft.modern.road;

import java.sql.*;
import java.util.*;

/** Permanent road ownership, original terrain and atomic treasury/world-job intents. */
public final class RoadStore {
    private RoadStore(){}
    public record Cell(RoadRules.Point point,String before,String nbt,String after,boolean floor,Long sourceBuild){public Cell(RoadRules.Point point,String before,String nbt,String after,boolean floor){this(point,before,nbt,after,floor,null);}}
    public record Road(long id,long town,long civ,String world,String state,long cost,long built,int health,int maximum){}
    public record At(Road road,boolean floor){}
    public record Job(String id,long road,String kind,long refund){}
    private static void tx(Connection db)throws SQLException{if(db.getAutoCommit())throw new SQLException("Road mutation requires a transaction");}
    public static void install(Connection db)throws SQLException{
        try(var s=db.createStatement()){
            s.executeUpdate("CREATE TABLE IF NOT EXISTS roads(build_id INTEGER PRIMARY KEY REFERENCES structure_builds(id) ON DELETE CASCADE,state TEXT NOT NULL,segments INTEGER NOT NULL,built_at INTEGER NOT NULL,health INTEGER NOT NULL,maximum INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS road_cells(build_id INTEGER NOT NULL REFERENCES roads(build_id) ON DELETE CASCADE,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,before_data TEXT NOT NULL,before_nbt TEXT,after_data TEXT NOT NULL,is_floor INTEGER NOT NULL,source_build INTEGER,PRIMARY KEY(build_id,x,y,z))");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS road_position ON road_cells(x,y,z)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS road_jobs(id TEXT PRIMARY KEY,build_id INTEGER NOT NULL REFERENCES roads(build_id) ON DELETE CASCADE,kind TEXT NOT NULL,refund INTEGER NOT NULL DEFAULT 0,state TEXT NOT NULL DEFAULT 'PREPARED')");
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_pending_road_job ON road_jobs(build_id) WHERE state='PREPARED'");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS road_job_cells(job_id TEXT NOT NULL REFERENCES road_jobs(id) ON DELETE CASCADE,ordinal INTEGER NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,block_data TEXT NOT NULL,block_nbt TEXT,PRIMARY KEY(job_id,ordinal))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS road_hits(id TEXT PRIMARY KEY,build_id INTEGER NOT NULL,actor TEXT NOT NULL,after_hp INTEGER NOT NULL,at INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS road_undo_targets(town_id INTEGER PRIMARY KEY REFERENCES towns(id) ON DELETE CASCADE,build_id INTEGER NOT NULL REFERENCES structure_builds(id) ON DELETE CASCADE)");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS pending_road_blocks_delete BEFORE DELETE ON structure_builds WHEN EXISTS(SELECT 1 FROM road_jobs WHERE build_id=OLD.id AND state='PREPARED') BEGIN SELECT RAISE(ABORT,'Road terrain operation is pending'); END");
        }
    }
    public static boolean installed(Connection db)throws SQLException{try(var s=db.createStatement();var r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='roads'")){return r.next();}}
    public static long authority(Connection db,UUID player,boolean peaceful)throws SQLException{
        if(peaceful && com.avrgaming.civcraft.modern.war.WarClockStore.active(db,System.currentTimeMillis()))throw new IllegalArgumentException("Дороги нельзя строить во время боевого периода.");
        try(var s=db.prepareStatement("SELECT t.id,EXISTS(SELECT 1 FROM civ_technologies tech WHERE tech.civ_id=t.civ_id AND tech.tech_id='tech_masonry') FROM residents r JOIN towns t ON t.id=r.town_id JOIN civilizations c ON c.id=t.civ_id WHERE r.uuid=? AND (t.mayor_uuid=r.uuid OR c.leader_uuid=r.uuid OR EXISTS(SELECT 1 FROM town_assistants a WHERE a.town_id=t.id AND a.player_uuid=r.uuid))")){
            s.setString(1,player.toString());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Дорогой управляет мэр, помощник или лидер цивилизации.");if(peaceful && !r.getBoolean(2))throw new IllegalArgumentException("Для дороги требуется каменная кладка.");return r.getLong(1);}
        }
    }
    public static Road road(Connection db,long id)throws SQLException{
        try(var s=db.prepareStatement("SELECT r.*,b.town_id,b.world,b.cost,t.civ_id,COALESCE(h.hitpoints,r.health) AS hp,COALESCE(h.maximum,r.maximum) AS max_hp FROM roads r JOIN structure_builds b ON b.id=r.build_id JOIN towns t ON t.id=b.town_id LEFT JOIN building_combat_health h ON h.build_id=r.build_id WHERE r.build_id=?")){s.setLong(1,id);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Дорога не найдена.");return new Road(id,r.getLong("town_id"),r.getLong("civ_id"),r.getString("world"),r.getString("state"),r.getLong("cost"),r.getLong("built_at"),r.getInt("hp"),r.getInt("max_hp"));}}
    }
    public static At at(Connection db,String world,int x,int y,int z)throws SQLException{
        if(!installed(db))return null;
        try(var s=db.prepareStatement("SELECT c.build_id,c.is_floor FROM road_cells c JOIN roads r ON r.build_id=c.build_id JOIN structure_builds b ON b.id=r.build_id WHERE b.world=? AND c.x=? AND c.y=? AND c.z=? AND r.state IN ('BUILDING','ACTIVE','REMOVING') AND NOT EXISTS(SELECT 1 FROM protected_blocks p WHERE p.world=b.world AND p.x=c.x AND p.y=c.y AND p.z=c.z AND (c.source_build IS NULL OR p.build_id<>c.source_build)) ORDER BY c.build_id DESC LIMIT 1")){
            s.setString(1,world);s.setInt(2,x);s.setInt(3,y);s.setInt(4,z);try(var r=s.executeQuery()){return r.next()?new At(road(db,r.getLong(1)),r.getBoolean(2)):null;}
        }
    }
    public static List<Cell> cells(Connection db,long id)throws SQLException{
        var result=new ArrayList<Cell>();try(var s=db.prepareStatement("SELECT * FROM road_cells WHERE build_id=? ORDER BY y,x,z")){s.setLong(1,id);try(var r=s.executeQuery()){while(r.next())result.add(new Cell(new RoadRules.Point(r.getInt("x"),r.getInt("y"),r.getInt("z")),r.getString("before_data"),r.getString("before_nbt"),r.getString("after_data"),r.getBoolean("is_floor"),r.getObject("source_build")==null?null:r.getLong("source_build")));}}return result;
    }
    public static long prepare(Connection db,UUID player,String world,RoadRules.Point origin,int segments,long cost,int health,List<Cell> cells,long builtAt,long now)throws SQLException{
        tx(db);long town=authority(db,player,true);
        try(var s=db.prepareStatement("UPDATE towns SET coins=coins-? WHERE id=? AND coins>=?")){s.setLong(1,cost);s.setLong(2,town);s.setLong(3,cost);if(s.executeUpdate()!=1)throw new IllegalArgumentException("Недостаточно монет в казне: дорога стоит "+cost+".");}
        long id;
        try(var s=db.prepareStatement("INSERT INTO structure_builds(builder_uuid,town_id,type,template,direction,world,x,y,z,template_path,queued_blocks,structure_id,display_name,cost,max_hitpoints,status,created_at,started_at,finish_at) VALUES(?,?,'structures','notused','north',?,?,?,?,'road.notused',?,'ti_road','Дорога',?,?,'ROAD_PREPARED',?,?,?)",Statement.RETURN_GENERATED_KEYS)){
            s.setString(1,player.toString());s.setLong(2,town);s.setString(3,world);s.setInt(4,origin.x());s.setInt(5,origin.y()-1);s.setInt(6,origin.z());s.setInt(7,cells.size());s.setLong(8,cost);s.setInt(9,health);s.setLong(10,now);s.setLong(11,now);s.setLong(12,now);s.executeUpdate();try(var r=s.getGeneratedKeys()){r.next();id=r.getLong(1);}
        }
        try(var s=db.prepareStatement("INSERT INTO roads VALUES(?,'BUILDING',?,?,?,?)")){s.setLong(1,id);s.setInt(2,segments);s.setLong(3,builtAt);s.setInt(4,health);s.setInt(5,health);s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO road_undo_targets VALUES(?,?) ON CONFLICT(town_id) DO UPDATE SET build_id=excluded.build_id")){s.setLong(1,town);s.setLong(2,id);s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO road_cells VALUES(?,?,?,?,?,?,?,?,?)")){for(var c:cells){s.setLong(1,id);s.setInt(2,c.point().x());s.setInt(3,c.point().y());s.setInt(4,c.point().z());s.setString(5,c.before());s.setString(6,c.nbt());s.setString(7,c.after());s.setBoolean(8,c.floor());if(c.sourceBuild()==null)s.setNull(9,Types.INTEGER);else s.setLong(9,c.sourceBuild());s.addBatch();}s.executeBatch();}
        job(db,"build:"+id,id,"BUILD",0,cells.stream().map(c->new Cell(c.point(),c.after(),null,c.after(),c.floor())).toList());return id;
    }
    public static void job(Connection db,String request,long id,String kind,long refund,List<Cell> images)throws SQLException{
        String world;try(var s=db.prepareStatement("SELECT world FROM structure_builds WHERE id=?")){s.setLong(1,id);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Road job building missing");world=r.getString(1);}}
        for(var image:images){var p=image.point();com.avrgaming.civcraft.modern.wall.WallStore.requireAreaFree(db,world,p.x(),p.y(),p.z(),1,1,1);}
        tx(db);try(var s=db.prepareStatement("INSERT INTO road_jobs(id,build_id,kind,refund) VALUES(?,?,?,?)")){s.setString(1,request);s.setLong(2,id);s.setString(3,kind);s.setLong(4,refund);s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO road_job_cells VALUES(?,?,?,?,?,?,?)")){int index=0;for(var c:images){s.setString(1,request);s.setInt(2,index++);s.setInt(3,c.point().x());s.setInt(4,c.point().y());s.setInt(5,c.point().z());s.setString(6,c.after());s.setString(7,c.nbt());s.addBatch();}s.executeBatch();}
    }
    public static List<Job> pending(Connection db)throws SQLException{var jobs=new ArrayList<Job>();try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM road_jobs WHERE state='PREPARED' ORDER BY rowid")){while(r.next())jobs.add(new Job(r.getString("id"),r.getLong("build_id"),r.getString("kind"),r.getLong("refund")));}return jobs;}
    public static List<Cell> images(Connection db,String id)throws SQLException{
        var result=new ArrayList<Cell>();try(var s=db.prepareStatement("SELECT * FROM road_job_cells WHERE job_id=? ORDER BY ordinal")){s.setString(1,id);try(var r=s.executeQuery()){while(r.next())result.add(new Cell(new RoadRules.Point(r.getInt("x"),r.getInt("y"),r.getInt("z")),r.getString("block_data"),r.getString("block_nbt"),r.getString("block_data"),false));}}return result;
    }
    public static void complete(Connection db,Job job,long now)throws SQLException{
        tx(db);try(var s=db.prepareStatement("UPDATE road_jobs SET state='DONE' WHERE id=? AND state='PREPARED'")){s.setString(1,job.id());if(s.executeUpdate()!=1)return;}
        var road=road(db,job.road());
        try(var s=db.prepareStatement("UPDATE roads SET state=? WHERE build_id=?")){s.setString(1,job.kind().equals("BUILD")?"ACTIVE":"RETIRED");s.setLong(2,job.road());s.executeUpdate();}
        try(var s=db.prepareStatement("UPDATE structure_builds SET status=?,completed_at=? WHERE id=?")){s.setString(1,job.kind().equals("BUILD")?"COMPLETE":"DEMOLISHED");s.setLong(2,now);s.setLong(3,job.road());s.executeUpdate();}
        if(job.refund()>0)try(var s=db.prepareStatement("UPDATE towns SET coins=coins+? WHERE id=? AND coins<=?")){s.setLong(1,job.refund());s.setLong(2,road.town());s.setLong(3,Long.MAX_VALUE-job.refund());if(s.executeUpdate()!=1)throw new SQLException("Road refund treasury overflow");}
    }
    public static boolean reserved(Connection db,String world,int x,int y,int z)throws SQLException{
        if(!installed(db))return false;
        try(var s=db.prepareStatement("SELECT 1 FROM road_job_cells c JOIN road_jobs j ON j.id=c.job_id JOIN structure_builds b ON b.id=j.build_id WHERE j.state='PREPARED' AND b.world=? AND c.x=? AND c.y=? AND c.z=? LIMIT 1")){s.setString(1,world);s.setInt(2,x);s.setInt(3,y);s.setInt(4,z);try(var r=s.executeQuery()){return r.next();}}
    }
    public static void requireAreaFree(Connection db,String world,int x,int y,int z,int width,int height,int depth,Long civ)throws SQLException{
        com.avrgaming.civcraft.modern.wall.WallStore.requireAreaFree(db,world,x,y,z,width,height,depth);
        if(!installed(db))return;
        try(var s=db.prepareStatement("SELECT 1 FROM road_cells c JOIN roads r ON r.build_id=c.build_id JOIN structure_builds b ON b.id=r.build_id JOIN towns t ON t.id=b.town_id WHERE b.world=? AND c.x BETWEEN ? AND ? AND c.y BETWEEN ? AND ? AND c.z BETWEEN ? AND ? AND r.state IN ('BUILDING','ACTIVE','REMOVING') AND (r.state<>'ACTIVE' OR ? IS NULL OR t.civ_id<>?) AND NOT EXISTS(SELECT 1 FROM protected_blocks p WHERE p.world=b.world AND p.x=c.x AND p.y=c.y AND p.z=c.z AND (c.source_build IS NULL OR p.build_id<>c.source_build)) LIMIT 1")){
            s.setString(1,world);s.setInt(2,x);s.setInt(3,Math.addExact(x,width-1));s.setInt(4,y);s.setInt(5,Math.addExact(y,height-1));s.setInt(6,z);s.setInt(7,Math.addExact(z,depth-1));if(civ==null){s.setNull(8,Types.INTEGER);s.setNull(9,Types.INTEGER);}else{s.setLong(8,civ);s.setLong(9,civ);}try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Чужая дорога или незавершённая работа с дорогой мешает размещению.");}
        }
    }
    public static int hit(Connection db,String request,long id,UUID actor,int damage,int hours,long now)throws SQLException{
        tx(db);try(var s=db.prepareStatement("SELECT after_hp FROM road_hits WHERE id=?")){s.setString(1,request);try(var r=s.executeQuery()){if(r.next())return r.getInt(1);}}
        var road=road(db,id);if(!road.state().equals("ACTIVE"))throw new IllegalArgumentException("Дорога ещё сохраняется.");
        var raid=RoadRules.raid(road.built(),hours,now);if(!raid.open())throw new IllegalArgumentException("Дорога защищена. Следующее окно рейда: "+java.time.Instant.ofEpochMilli(raid.next()).atZone(java.time.ZoneId.systemDefault())+".");
        if(!com.avrgaming.civcraft.modern.war.WarClockStore.hasWars(db,road.civ()))throw new IllegalArgumentException("Владелец дороги не участвует ни в одной объявленной войне.");
        if(road.health()==0)throw new IllegalArgumentException("Дорога уже потеряла всё здоровье.");int hp=Math.max(0,road.health()-damage);
        try(var s=db.prepareStatement("UPDATE roads SET health=? WHERE build_id=?")){s.setInt(1,hp);s.setLong(2,id);s.executeUpdate();}
        // Original Road binds no StructureBlocks: reaching zero HP does not erase its separate RoadBlocks.
        try(var s=db.prepareStatement("INSERT INTO building_combat_health VALUES(?,?,?,'DAMAGED') ON CONFLICT(build_id) DO UPDATE SET hitpoints=excluded.hitpoints,maximum=excluded.maximum,phase='DAMAGED'")){s.setLong(1,id);s.setInt(2,hp);s.setInt(3,road.maximum());s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO road_hits VALUES(?,?,?,?,?)")){s.setString(1,request);s.setLong(2,id);s.setString(3,actor.toString());s.setInt(4,hp);s.setLong(5,now);s.executeUpdate();}return hp;
    }
}
