package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.util.*;
public final class BuildingRepairStore {
    private BuildingRepairStore() {}
    public record Offer(long build,long town,long civ,String name,String type,String status,long cost,String template,String world,int x,int y,int z) {}
    public record Block(String world,int x,int y,int z,String data) {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS building_repair_jobs(id INTEGER PRIMARY KEY AUTOINCREMENT,build_id INTEGER NOT NULL REFERENCES structure_builds(id) ON DELETE CASCADE,actor TEXT NOT NULL,cost INTEGER NOT NULL,state TEXT NOT NULL CHECK(state IN ('PREPARED','COMPLETE')),created_at INTEGER NOT NULL)");
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_pending_building_repair ON building_repair_jobs(build_id) WHERE state='PREPARED'");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS building_repair_blocks(job_id INTEGER NOT NULL REFERENCES building_repair_jobs(id) ON DELETE CASCADE,world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,data TEXT NOT NULL,PRIMARY KEY(job_id,world,x,y,z))");
        }
    }
    public static Offer offer(Connection db,long build,UUID actor)throws SQLException {
        try(var s=db.prepareStatement("SELECT b.*,t.civ_id,t.mayor_uuid,c.leader_uuid,r.civ_id AS actor_civ FROM structure_builds b JOIN towns t ON t.id=b.town_id JOIN civilizations c ON c.id=t.civ_id LEFT JOIN residents r ON r.uuid=? WHERE b.id=?")) {
            s.setString(1,actor.toString());s.setLong(2,build);try(var r=s.executeQuery()) {
                if(!r.next())throw new IllegalArgumentException("Здание не найдено.");
                if(r.getObject("actor_civ")==null || r.getLong("actor_civ")!=r.getLong("civ_id") || (!actor.toString().equals(r.getString("mayor_uuid")) && !actor.toString().equals(r.getString("leader_uuid"))))throw new IllegalArgumentException("Ремонт доступен мэру или лидеру своей цивилизации.");
                return new Offer(build,r.getLong("town_id"),r.getLong("civ_id"),r.getString("display_name"),r.getString("structure_id"),r.getString("status"),r.getLong("cost")/2,r.getString("template_path"),r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"));
            }
        }
    }
    public static void validate(Connection db,Offer offer,String technology,long now)throws SQLException {
        com.avrgaming.civcraft.modern.trade.TradeShipOperations.requireBuildUnlocked(db,offer.build());
        com.avrgaming.civcraft.modern.town.ProductionStore.requireBuildUnlocked(db,offer.build());
        try(var s=db.prepareStatement("SELECT 1 FROM war_sessions WHERE state='ACTIVE' AND start_at<=?")){s.setLong(1,now);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Во время боевого периода ремонт запрещён.");}}
        if(!offer.status().equals("WAR_DESTROYED"))throw new IllegalArgumentException("Ремонтируется только разрушенное здание.");
        if(offer.type().equals("s_townhall") || offer.type().equals("s_capitol") || offer.type().startsWith("w_"))throw new IllegalArgumentException("Для этого здания предусмотрен отдельный ремонт.");
        for(String tech:(technology==null || technology.isBlank()?List.<String>of():Arrays.stream(technology.split("[,;\\s]+")).filter(t->!t.isBlank()).toList()))try(var s=db.prepareStatement("SELECT 1 FROM civ_technologies WHERE civ_id=? AND tech_id=?")) {
            s.setLong(1,offer.civ());s.setString(2,tech);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Требуется технология: "+tech);}
        }
        try(var s=db.prepareStatement("SELECT 1 FROM building_combat_health h JOIN building_ruin_jobs j ON j.build_id=h.build_id WHERE h.build_id=? AND h.hitpoints=0 AND h.phase='DESTROYED' AND j.state='COMPLETE'")) {
            s.setLong(1,offer.build());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Обвал здания ещё не завершён.");}
        }
    }
    public static long prepare(Connection db,long build,UUID actor,String technology,List<Block> blocks,long now)throws SQLException {
        var offer=offer(db,build,actor);validate(db,offer,technology,now);
        try(var s=db.prepareStatement("UPDATE towns SET coins=coins-? WHERE id=? AND coins>=?")){s.setLong(1,offer.cost());s.setLong(2,offer.town());s.setLong(3,offer.cost());if(s.executeUpdate()!=1)throw new IllegalArgumentException("В казне недостаточно монет: требуется "+offer.cost()+".");}
        long id;
        try(var s=db.prepareStatement("INSERT INTO building_repair_jobs(build_id,actor,cost,state,created_at) VALUES(?,?,?,'PREPARED',?) RETURNING id")) {
            s.setLong(1,build);s.setString(2,actor.toString());s.setLong(3,offer.cost());s.setLong(4,now);try(var r=s.executeQuery()){r.next();id=r.getLong(1);}
        }
        try(var s=db.prepareStatement("INSERT INTO building_repair_blocks VALUES(?,?,?,?,?,?)")) {
            for(var b:blocks){s.setLong(1,id);s.setString(2,b.world());s.setInt(3,b.x());s.setInt(4,b.y());s.setInt(5,b.z());s.setString(6,b.data());s.addBatch();}s.executeBatch();
        }
        try(var s=db.prepareStatement("UPDATE structure_builds SET status='WAR_REPAIR_PENDING' WHERE id=? AND status='WAR_DESTROYED'")){s.setLong(1,build);if(s.executeUpdate()!=1)throw new SQLException("Repair target changed");}
        return id;
    }
    public static Long next(Connection db)throws SQLException {try(var s=db.createStatement();var r=s.executeQuery("SELECT id FROM building_repair_jobs WHERE state='PREPARED' ORDER BY id LIMIT 1")){return r.next()?r.getLong(1):null;}}
    public static List<Block> blocks(Connection db,long job)throws SQLException {
        var result=new ArrayList<Block>();try(var s=db.prepareStatement("SELECT * FROM building_repair_blocks WHERE job_id=? ORDER BY world,y,x,z")) {
            s.setLong(1,job);try(var r=s.executeQuery()){while(r.next())result.add(new Block(r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getString("data")));}
        }return List.copyOf(result);
    }
    public static Long complete(Connection db,long job)throws SQLException {
        long build;try(var s=db.prepareStatement("SELECT build_id,state FROM building_repair_jobs WHERE id=?")){s.setLong(1,job);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing repair job");if(r.getString(2).equals("COMPLETE"))return null;build=r.getLong(1);}}
        try(var s=db.prepareStatement("UPDATE structure_builds SET status='COMPLETE' WHERE id=? AND status='WAR_REPAIR_PENDING'")){s.setLong(1,build);if(s.executeUpdate()!=1)throw new SQLException("Repair target changed");}
        int maximum=BuildingDamageStore.health(db,build).maximum();
        try(var s=db.prepareStatement("UPDATE building_combat_health SET hitpoints=?,maximum=?,phase='DAMAGED' WHERE build_id=? AND phase='DESTROYED'")){s.setInt(1,maximum);s.setInt(2,maximum);s.setLong(3,build);if(s.executeUpdate()!=1)throw new SQLException("Repair health changed");}
        try(var s=db.prepareStatement("DELETE FROM building_ruin_jobs WHERE build_id=?")){s.setLong(1,build);s.executeUpdate();}
        try(var s=db.prepareStatement("UPDATE building_repair_jobs SET state='COMPLETE' WHERE id=?")){s.setLong(1,job);s.executeUpdate();}return build;
    }
}
