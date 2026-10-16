package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import java.util.*;

/** Wall clock accrues only while the server runs; the production clock counts applied growth cycles. */
public final class FarmGrowthStore {
    private FarmGrowthStore() {}
    public record Cell(FarmGrowthPlan.Position position,String before,String after) {
        public Cell{Objects.requireNonNull(position);if(before==null || after==null || before.isBlank() || after.isBlank() || before.equals(after))throw new IllegalArgumentException("Invalid growth change");}
    }
    public record State(long build,long period,long tick,long missed) {}
    private static void transaction(Connection db)throws SQLException{if(db.getAutoCommit())throw new SQLException("Farm growth requires a transaction");}
    public static void install(Connection db)throws SQLException{
        try(var s=db.createStatement()){
            s.executeUpdate("CREATE TABLE IF NOT EXISTS farm_growth_state(build_id INTEGER PRIMARY KEY REFERENCES structure_builds(id) ON DELETE CASCADE,period INTEGER NOT NULL CHECK(period>=1000),last_tick INTEGER NOT NULL CHECK(last_tick>=0),missed INTEGER NOT NULL DEFAULT 0 CHECK(missed>=0))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS farm_growth_cycles(operation_id TEXT PRIMARY KEY REFERENCES resource_production_operations(id) ON DELETE CASCADE,build_id INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS farm_growth_cells(operation_id TEXT NOT NULL REFERENCES farm_growth_cycles(operation_id) ON DELETE CASCADE,world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,before_state TEXT NOT NULL,after_state TEXT NOT NULL,PRIMARY KEY(world,x,y,z))");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS farm_growth_cells_operation ON farm_growth_cells(operation_id)");
        }
    }
    public static State state(Connection db,long build)throws SQLException{
        try(var s=db.prepareStatement("SELECT period,last_tick,missed FROM farm_growth_state WHERE build_id=?")){s.setLong(1,build);try(var r=s.executeQuery()){return r.next()?new State(build,r.getLong(1),r.getLong(2),r.getLong(3)):null;}}
    }
    public static void start(Connection db,long period,long tick)throws SQLException{
        transaction(db);valid(period,tick);
        if(!ProductionStore.pending(db).isEmpty())throw new SQLException("Recover production before starting farms");
        try(var s=db.prepareStatement("UPDATE farm_growth_state SET period=?,last_tick=?")){s.setLong(1,period);s.setLong(2,tick);s.executeUpdate();}
        try(var s=db.prepareStatement("UPDATE resource_production_clock SET period=? WHERE build_id IN (SELECT id FROM structure_builds WHERE structure_id='ti_farm')")){s.setLong(1,period);s.executeUpdate();}
    }
    public static State observe(Connection db,long build,long period,long tick)throws SQLException{
        transaction(db);valid(period,tick);
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO farm_growth_state VALUES(?,?,?,0)")){s.setLong(1,build);s.setLong(2,period);s.setLong(3,tick);s.executeUpdate();}
        var before=state(db,build);if(before.period()!=period)throw new SQLException("Farm period changed without startup");
        long elapsed=Math.max(0,tick-before.tick());long missed=Math.addExact(before.missed(),elapsed);
        try(var s=db.prepareStatement("UPDATE farm_growth_state SET last_tick=MAX(last_tick,?),missed=? WHERE build_id=?")){s.setLong(1,tick);s.setLong(2,missed);s.setLong(3,build);s.executeUpdate();}
        return state(db,build);
    }
    private static void valid(long period,long tick){if(period<1000 || tick<0)throw new IllegalArgumentException("Invalid farm clock");}
    public static ProductionStore.Pending prepare(Connection db,String id,long build,List<Cell> cells,byte[] emptyContainers)throws SQLException{
        transaction(db);var state=state(db,build);if(state==null || state.missed()<1)throw new SQLException("No farm growth cycle is due");
        String world;int x,z;
        try(var s=db.prepareStatement("SELECT world,x,z FROM active_structure_builds WHERE id=? AND structure_id='ti_farm' AND status='COMPLETE' AND town_id IS NOT NULL")){s.setLong(1,build);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Farm is not complete");world=r.getString(1);x=Math.floorDiv(r.getInt(2),16);z=Math.floorDiv(r.getInt(3),16);}}
        if(FarmPlots.registered(db,world,x*16,z*16).orElseThrow().farm()!=build)throw new SQLException("Farm chunk belongs to another registration");
        for(var cell:cells){var p=cell.position();if(!world.equals(p.world()) || Math.floorDiv(p.x(),16)!=x || Math.floorDiv(p.z(),16)!=z)throw new IllegalArgumentException("Growth outside registered farm chunk");
            try(var s=db.prepareStatement("SELECT 1 FROM resource_production_plantings WHERE world=? AND x=? AND y=? AND z=?")){s.setString(1,p.world());s.setInt(2,p.x());s.setInt(3,p.y());s.setInt(4,p.z());try(var r=s.executeQuery()){if(r.next())throw new SQLException("Windmill planting is pending");}}
        }
        var clock=ProductionStore.initialize(db,build,state.period(),0);
        var pending=ProductionStore.prepare(db,id,clock,Math.addExact(clock.tick(),1),cells.size(),emptyContainers);
        try(var s=db.prepareStatement("INSERT INTO farm_growth_cycles VALUES(?,?)")){s.setString(1,id);s.setLong(2,build);s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO farm_growth_cells VALUES(?,?,?,?,?,?,?)")){
            for(var cell:cells){var p=cell.position();s.setString(1,id);s.setString(2,p.world());s.setInt(3,p.x());s.setInt(4,p.y());s.setInt(5,p.z());s.setString(6,cell.before());s.setString(7,cell.after());s.addBatch();}s.executeBatch();
        }return pending;
    }
    public static List<Cell> cells(Connection db,String id)throws SQLException{
        var result=new ArrayList<Cell>();try(var s=db.prepareStatement("SELECT world,x,y,z,before_state,after_state FROM farm_growth_cells WHERE operation_id=? ORDER BY world,x,y,z")){s.setString(1,id);try(var r=s.executeQuery()){while(r.next())result.add(new Cell(new FarmGrowthPlan.Position(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4)),r.getString(5),r.getString(6)));}}return List.copyOf(result);
    }
    static void complete(Connection db,String id)throws SQLException{
        try(var s=db.prepareStatement("SELECT build_id FROM farm_growth_cycles WHERE operation_id=?")){s.setString(1,id);try(var r=s.executeQuery()){if(!r.next())return;long build=r.getLong(1);
            try(var u=db.prepareStatement("UPDATE farm_growth_state SET missed=missed-1 WHERE build_id=? AND missed>0")){u.setLong(1,build);if(u.executeUpdate()!=1)throw new SQLException("Farm growth credit missing");}
        }}
        // Explicit delete also works for connections where FK enforcement has not been enabled.
        try(var s=db.prepareStatement("DELETE FROM farm_growth_cells WHERE operation_id=?")){s.setString(1,id);s.executeUpdate();}
        try(var s=db.prepareStatement("DELETE FROM farm_growth_cycles WHERE operation_id=?")){s.setString(1,id);s.executeUpdate();}
    }
}
