package com.avrgaming.civcraft.modern.wall;

import com.avrgaming.civcraft.modern.trade.GoodieChestStore;
import com.avrgaming.civcraft.modern.war.*;
import java.sql.*;
import java.util.*;

/** Wall.repairStructure restores recorded WallBlocks, not a rectangular .def template. */
public final class WallRepairStore {
    private WallRepairStore() {}
    public record Offer(WallRemovalStore.Wall wall,long cost,int maximum) {}
    private static void tx(Connection db)throws SQLException{if(db.getAutoCommit())throw new SQLException("Wall repair requires a transaction");}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS wall_repairs(job_id TEXT PRIMARY KEY REFERENCES wall_jobs(id) ON DELETE CASCADE,maximum INTEGER NOT NULL CHECK(maximum>0),cost INTEGER NOT NULL CHECK(cost>=0),actor_uuid TEXT NOT NULL)");}
    }
    public static Offer offer(Connection db,long build,UUID actor,WallCatalog.Profile profile)throws SQLException {
        var owner=WallPlacement.authority(db,actor,profile,true);var wall=WallRemovalStore.wall(db,build);
        if(owner.town()!=wall.town() || !wall.type().equals(profile.type()))throw new IllegalArgumentException("Стена принадлежит другому городу.");
        if(!wall.state().equals("ACTIVE") || !wall.status().equals("WAR_DESTROYED"))throw new IllegalArgumentException("Ремонтировать можно только разрушенную стену после завершения её разрушения.");
        var health=BuildingDamageStore.health(db,build);
        if(health.hitpoints()!=0 || !health.phase().equals("DESTROYED"))throw new IllegalArgumentException("Разрушение стены ещё не завершено.");
        // Original Structure.getRepairCost casts the configured base price to int before integer division.
        return new Offer(wall,((int)profile.baseCost())/2,health.maximum());
    }
    public static void prepare(Connection db,String request,long build,UUID actor,WallCatalog.Profile profile,List<WallStore.Image> images,long now)throws SQLException {
        tx(db);var offer=offer(db,build,actor,profile);var cells=WallRemovalStore.cells(db,build);
        if(images.size()!=cells.size() || images.isEmpty())throw new SQLException("Incomplete wall repair image");
        var expected=new HashMap<WallRules.Point,String>();for(var cell:cells)expected.put(cell.point(),cell.after());
        var positions=new ArrayList<GoodieChestStore.Position>();
        for(var image:images) {
            var p=image.point();if(!Objects.equals(expected.remove(p),image.data()) || image.nbt()!=null)throw new SQLException("Wall repair differs from recorded geometry");
            positions.add(new GoodieChestStore.Position(offer.wall().world(),p.x(),p.y(),p.z()));
        }
        if(!expected.isEmpty())throw new SQLException("Missing wall repair cells");
        GoodieChestStore.requirePositionsAvailable(db,positions);requireOwnership(db,offer.wall());
        try(var s=db.prepareStatement("UPDATE towns SET coins=coins-? WHERE id=? AND coins>=?")) {
            s.setLong(1,offer.cost());s.setLong(2,offer.wall().town());s.setLong(3,offer.cost());if(s.executeUpdate()!=1)throw new IllegalArgumentException("В казне недостаточно монет для ремонта стены.");
        }
        try(var s=db.prepareStatement("UPDATE structure_builds SET status='WAR_REPAIR_PENDING' WHERE id=? AND status='WAR_DESTROYED'")){s.setLong(1,build);if(s.executeUpdate()!=1)throw new SQLException("Wall changed before repair");}
        try(var s=db.prepareStatement("INSERT INTO wall_jobs(id,build_id,kind,state) VALUES(?,?,'REPAIR','PREPARED')")){s.setString(1,request);s.setLong(2,build);s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO wall_repairs VALUES(?,?,?,?)")){s.setString(1,request);s.setInt(2,offer.maximum());s.setLong(3,offer.cost());s.setString(4,actor.toString());s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO wall_job_cells VALUES(?,?,?,?,?,?,?)")) {
            int index=0;for(var image:images){var p=image.point();s.setString(1,request);s.setInt(2,index++);s.setInt(3,p.x());s.setInt(4,p.y());s.setInt(5,p.z());s.setString(6,image.data());s.setString(7,image.nbt());s.addBatch();}s.executeBatch();
        }
    }
    private static void requireOwnership(Connection db,WallRemovalStore.Wall wall)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM wall_cells c JOIN protected_blocks p ON p.world=? AND p.x=c.x AND p.y=c.y AND p.z=c.z WHERE c.build_id=? AND p.build_id<>c.build_id LIMIT 1")) {
            s.setString(1,wall.world());s.setLong(2,wall.id());try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Другие защищённые блоки мешают ремонту стены.");}
        }
    }
    public static void complete(Connection db,WallStore.Job job,long now)throws SQLException {
        tx(db);if(!job.kind().equals("REPAIR"))throw new SQLException("Not a wall repair job");
        int maximum;
        try(var s=db.prepareStatement("SELECT j.state,r.maximum FROM wall_jobs j JOIN wall_repairs r ON r.job_id=j.id WHERE j.id=? AND j.build_id=? AND j.kind='REPAIR'")) {
            s.setString(1,job.id());s.setLong(2,job.build());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing wall repair job");if(r.getString(1).equals("DONE"))return;maximum=r.getInt(2);}
        }
        var wall=WallRemovalStore.wall(db,job.build());if(!wall.state().equals("ACTIVE") || !wall.status().equals("WAR_REPAIR_PENDING"))throw new SQLException("Wall repair state changed");requireOwnership(db,wall);
        try(var s=db.prepareStatement("INSERT INTO protected_blocks(world,x,y,z,build_id,material) VALUES(?,?,?,?,?,?) ON CONFLICT(world,x,y,z) DO UPDATE SET build_id=excluded.build_id,material=excluded.material")) {
            for(var cell:WallRemovalStore.cells(db,job.build())){var p=cell.point();s.setString(1,wall.world());s.setInt(2,p.x());s.setInt(3,p.y());s.setInt(4,p.z());s.setLong(5,wall.id());s.setString(6,WallUndoRules.material(cell.after()).toUpperCase(Locale.ROOT));s.addBatch();}s.executeBatch();
        }
        try(var s=db.prepareStatement("INSERT INTO building_combat_health VALUES(?,?,?,'DAMAGED') ON CONFLICT(build_id) DO UPDATE SET hitpoints=excluded.hitpoints,maximum=excluded.maximum,phase='DAMAGED'")){s.setLong(1,job.build());s.setInt(2,maximum);s.setInt(3,maximum);s.executeUpdate();}
        try(var s=db.prepareStatement("UPDATE structure_builds SET status='COMPLETE',completed_at=? WHERE id=? AND status='WAR_REPAIR_PENDING'")){s.setLong(1,now);s.setLong(2,job.build());if(s.executeUpdate()!=1)throw new SQLException("Wall repair completion changed");}
        try(var s=db.prepareStatement("UPDATE wall_jobs SET state='DONE' WHERE id=? AND state='PREPARED'")){s.setString(1,job.id());if(s.executeUpdate()!=1)throw new SQLException("Wall repair job completion changed");}
    }
}
