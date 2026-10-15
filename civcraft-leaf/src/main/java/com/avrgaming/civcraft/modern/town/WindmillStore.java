package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import java.util.*;

/** Pending crop positions accompany the source chest image in the same production transaction. */
public final class WindmillStore {
    private WindmillStore() {}
    public record Bed(WindmillPlan.Spot spot,WindmillPlan.Crop crop,String before) {
        public Bed {
            Objects.requireNonNull(spot);Objects.requireNonNull(crop);
            if(!Set.of("minecraft:air","minecraft:cave_air","minecraft:void_air").contains(before))throw new IllegalArgumentException("Windmill requires an empty planting position");
        }
        public String after(){return "minecraft:"+crop.block().toLowerCase(Locale.ROOT)+"[age=0]";}
    }
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS resource_production_plantings(operation_id TEXT NOT NULL REFERENCES resource_production_operations(id) ON DELETE CASCADE,farm_id INTEGER NOT NULL,world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,crop TEXT NOT NULL,before_state TEXT NOT NULL,PRIMARY KEY(world,x,y,z))");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS production_plantings_operation ON resource_production_plantings(operation_id)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS production_plantings_farm ON resource_production_plantings(farm_id)");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS planting_blocks_farm_delete BEFORE DELETE ON structure_builds WHEN EXISTS(SELECT 1 FROM resource_production_plantings p JOIN resource_production_operations o ON o.id=p.operation_id WHERE p.farm_id=OLD.id AND o.status='PREPARED') BEGIN SELECT RAISE(ABORT,'Farm planting is pending'); END");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS planting_blocks_farm_owner_change BEFORE UPDATE OF town_id ON structure_builds WHEN NEW.town_id IS NOT OLD.town_id AND EXISTS(SELECT 1 FROM resource_production_plantings p JOIN resource_production_operations o ON o.id=p.operation_id WHERE p.farm_id=OLD.id AND o.status='PREPARED') BEGIN SELECT RAISE(ABORT,'Farm planting is pending'); END");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS planting_blocks_farm_civ_change BEFORE UPDATE OF civ_id ON towns WHEN NEW.civ_id IS NOT OLD.civ_id AND EXISTS(SELECT 1 FROM resource_production_plantings p JOIN resource_production_operations o ON o.id=p.operation_id JOIN structure_builds f ON f.id=p.farm_id WHERE f.town_id=OLD.id AND o.status='PREPARED') BEGIN SELECT RAISE(ABORT,'Farm planting is pending'); END");
        }
    }
    public static ProductionStore.Pending prepare(Connection db,String id,ProductionStore.Clock before,long tick,List<Bed> beds,byte[] containers,long now)throws SQLException {
        String world;int x,z;
        try(var s=db.prepareStatement("SELECT world,x,z FROM structure_builds WHERE id=? AND structure_id='ti_windmill' AND status='COMPLETE'")) {
            s.setLong(1,before.build());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Мельница не завершена.");world=r.getString(1);x=r.getInt(2);z=r.getInt(3);}
        }
        if(!beds.isEmpty())try(var s=db.prepareStatement("SELECT 1 FROM war_sessions WHERE state='ACTIVE' AND start_at<=? AND end_at>?")){s.setLong(1,now);s.setLong(2,now);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Во время боевого периода мельница не сажает урожай.");}}
        var farms=new HashMap<Long,FarmPlots.Plot>();for(var farm:FarmPlots.adjacent(db,world,x,z))farms.put(farm.farm(),farm);
        for(var bed:beds){var farm=farms.get(bed.spot().farm());if(farm==null || !farm.world().equals(bed.spot().world()) || farm.x()!=Math.floorDiv(bed.spot().x(),16) || farm.z()!=Math.floorDiv(bed.spot().z(),16))throw new IllegalArgumentException("Грядка находится вне соседней завершённой фермы.");}
        var pending=ProductionStore.prepare(db,id,before,tick,beds.size(),containers);
        try(var s=db.prepareStatement("INSERT INTO resource_production_plantings VALUES(?,?,?,?,?,?,?,?)")) {
            for(var bed:beds){s.setString(1,id);s.setLong(2,bed.spot().farm());s.setString(3,bed.spot().world());s.setInt(4,bed.spot().x());s.setInt(5,bed.spot().y());s.setInt(6,bed.spot().z());s.setString(7,bed.crop().name());s.setString(8,bed.before());s.addBatch();}s.executeBatch();
        }
        return pending;
    }
    public static List<Bed> beds(Connection db,String operation)throws SQLException {
        var result=new ArrayList<Bed>();try(var s=db.prepareStatement("SELECT farm_id,world,x,y,z,crop,before_state FROM resource_production_plantings WHERE operation_id=? ORDER BY world,x,y,z")) {
            s.setString(1,operation);try(var r=s.executeQuery()){while(r.next())result.add(new Bed(new WindmillPlan.Spot(r.getLong(1),r.getString(2),r.getInt(3),r.getInt(4),r.getInt(5)),WindmillPlan.Crop.valueOf(r.getString(6)),r.getString(7)));}
        }return List.copyOf(result);
    }
}
