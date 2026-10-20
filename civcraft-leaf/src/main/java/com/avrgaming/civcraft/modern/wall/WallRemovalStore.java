package com.avrgaming.civcraft.modern.wall;

import java.sql.*;
import java.util.*;
import com.avrgaming.civcraft.modern.trade.GoodieChestStore;
import com.avrgaming.civcraft.modern.war.SiegeWorldStore;

/** Undo restores terrain; demolish only retires registration, as Town.demolish/deleteSkipUndo. */
public final class WallRemovalStore {
    private WallRemovalStore() {}
    public record Wall(long id,long town,String world,String type,String state,String status,long cost,int height,double segmentCost,int segments,int hp,int maximum,WallRules.Point origin) {}
    private static void tx(Connection db)throws SQLException{if(db.getAutoCommit())throw new SQLException("Wall removal requires a transaction");}
    public static void install(Connection db)throws SQLException {
        boolean refund=false;try(var s=db.createStatement();var r=s.executeQuery("PRAGMA table_info(wall_jobs)")){while(r.next())if(r.getString("name").equals("refund"))refund=true;}
        try(var s=db.createStatement()) {
            if(!refund)s.executeUpdate("ALTER TABLE wall_jobs ADD COLUMN refund INTEGER NOT NULL DEFAULT 0");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS wall_undo_targets(town_id INTEGER PRIMARY KEY REFERENCES towns(id) ON DELETE CASCADE,build_id INTEGER NOT NULL REFERENCES walls(build_id) ON DELETE CASCADE)");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS invalidate_wall_undo_on_build AFTER INSERT ON structure_builds WHEN NEW.town_id IS NOT NULL BEGIN DELETE FROM wall_undo_targets WHERE town_id=NEW.town_id; END");
        }
    }
    public static Wall wall(Connection db,long id)throws SQLException {
        try(var s=db.prepareStatement("""
            SELECT w.*,b.town_id,b.world,b.structure_id,b.status,b.cost,b.x,b.y,b.z,COALESCE(h.hitpoints,b.max_hitpoints) AS current_hp,COALESCE(h.maximum,b.max_hitpoints) AS current_max
            FROM walls w JOIN structure_builds b ON b.id=w.build_id LEFT JOIN building_combat_health h ON h.build_id=b.id WHERE w.build_id=?
            """)) {
            s.setLong(1,id);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Стена не найдена.");return new Wall(id,r.getLong("town_id"),r.getString("world"),r.getString("structure_id"),r.getString("state"),r.getString("status"),r.getLong("cost"),r.getInt("wall_height"),r.getDouble("segment_cost"),r.getInt("segments"),r.getInt("current_hp"),r.getInt("current_max"),new WallRules.Point(r.getInt("x"),r.getInt("y"),r.getInt("z")));}
        }
    }
    public static Long at(Connection db,String world,int x,int z)throws SQLException {
        try(var s=db.prepareStatement("SELECT w.build_id FROM wall_columns c JOIN walls w ON w.build_id=c.build_id JOIN structure_builds b ON b.id=w.build_id WHERE b.world=? AND c.x=? AND c.z=? AND w.state<>'RETIRED' ORDER BY w.build_id DESC LIMIT 1")) {
            s.setString(1,world);s.setInt(2,x);s.setInt(3,z);try(var r=s.executeQuery()){return r.next()?r.getLong(1):null;}
        }
    }
    public static Long lastWall(Connection db,long town)throws SQLException {
        try(var s=db.prepareStatement("SELECT id,structure_id FROM structure_builds WHERE town_id=? ORDER BY id DESC LIMIT 1")) {
            s.setLong(1,town);try(var r=s.executeQuery()){if(!r.next())return null;String type=r.getString(2);return "ti_wall".equals(type) || "ti_fortifiedwall".equals(type)?r.getLong(1):null;}
        }
    }
    public static List<WallPlacement.Cell> cells(Connection db,long build)throws SQLException {
        var result=new ArrayList<WallPlacement.Cell>();try(var s=db.prepareStatement("SELECT * FROM wall_cells WHERE build_id=? ORDER BY ordinal")) {
            s.setLong(1,build);try(var r=s.executeQuery()){while(r.next())result.add(new WallPlacement.Cell(new WallRules.Point(r.getInt("x"),r.getInt("y"),r.getInt("z")),r.getString("before_data"),r.getString("before_nbt"),r.getString("after_data"),r.getObject("source_build")==null?null:r.getLong("source_build")));}
        }return List.copyOf(result);
    }
    private static Wall owned(Connection db,UUID actor,long id,WallCatalog.Profile profile)throws SQLException {
        var owner=WallPlacement.authority(db,actor,profile,false);var wall=wall(db,id);
        if(owner.town()!=wall.town() || !profile.type().equals(wall.type()))throw new IllegalArgumentException("Стена принадлежит другому городу.");
        if(!wall.state().equals("ACTIVE") || !Set.of("COMPLETE","WAR_DESTROYED").contains(wall.status()))throw new IllegalArgumentException("Стена ещё устанавливается, восстанавливается или уже удалена.");
        return wall;
    }
    public static long undo(Connection db,String request,UUID actor,long id,WallCatalog.Profile profile)throws SQLException {
        tx(db);var wall=owned(db,actor,id,profile);
        // Both wall implementations define processUndo; include FortifiedWall despite the old Town type-check omission.
        try(var s=db.prepareStatement("SELECT 1 FROM wall_undo_targets u WHERE u.town_id=? AND u.build_id=? AND u.build_id=(SELECT MAX(b.id) FROM structure_builds b WHERE b.town_id=u.town_id)")) {
            s.setLong(1,wall.town());s.setLong(2,id);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Эта стена уже не является последней доступной для отмены постройкой.");}
        }
        var cells=cells(db,id);var positions=new ArrayList<GoodieChestStore.Position>();var plants=SiegeWorldStore.productionLocks(db,wall.world());
        for(var cell:cells) {
            var p=cell.point();if(plants.contains(new SiegeWorldStore.Position(p.x(),p.y(),p.z())))throw new IllegalArgumentException("На месте стены ещё сохраняются растения.");
            positions.add(new GoodieChestStore.Position(wall.world(),p.x(),p.y(),p.z()));
        }
        GoodieChestStore.requirePositionsAvailable(db,positions);requireOwnership(db,wall);
        long refund=WallUndoRules.refund(cells,wall.segmentCost(),wall.height());
        try(var s=db.prepareStatement("SELECT 1 FROM towns WHERE id=? AND coins<=?")) {
            s.setLong(1,wall.town());s.setLong(2,Long.MAX_VALUE-refund);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Казна города не вмещает возврат стоимости стены.");}
        }
        try(var s=db.prepareStatement("UPDATE walls SET state='REMOVING' WHERE build_id=? AND state='ACTIVE'")){s.setLong(1,id);if(s.executeUpdate()!=1)throw new SQLException("Wall changed before undo");}
        try(var s=db.prepareStatement("UPDATE structure_builds SET status='WALL_REMOVE_PENDING' WHERE id=? AND status IN ('COMPLETE','WAR_DESTROYED')")){s.setLong(1,id);if(s.executeUpdate()!=1)throw new SQLException("Wall status changed before undo");}
        try(var s=db.prepareStatement("INSERT INTO wall_jobs(id,build_id,kind,state,refund) VALUES(?,?,'UNDO','PREPARED',?)")){s.setString(1,request);s.setLong(2,id);s.setLong(3,refund);s.executeUpdate();}
        // The final Wall.delete/undoFromTemplate in the original restores all cells, including restricted ones.
        try(var s=db.prepareStatement("INSERT INTO wall_job_cells SELECT ?,ordinal,x,y,z,before_data,before_nbt FROM wall_cells WHERE build_id=?")){s.setString(1,request);s.setLong(2,id);s.executeUpdate();}
        try(var s=db.prepareStatement("DELETE FROM wall_undo_targets WHERE town_id=?")){s.setLong(1,wall.town());s.executeUpdate();}
        return refund;
    }
    public static void demolish(Connection db,String request,UUID actor,long id,WallCatalog.Profile profile,long now)throws SQLException {
        tx(db);var wall=owned(db,actor,id,profile);if(!profile.allowDemolish())throw new IllegalArgumentException("Эту стену нельзя снять с учёта.");
        try(var s=db.prepareStatement("INSERT INTO wall_jobs(id,build_id,kind,state,refund) VALUES(?,?,'DEMOLISH','DONE',0)")){s.setString(1,request);s.setLong(2,id);s.executeUpdate();}
        retire(db,wall,now); // No world mutation and no refund: original deleteSkipUndo.
    }
    private static void requireOwnership(Connection db,Wall wall)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM wall_cells c JOIN protected_blocks p ON p.world=? AND p.x=c.x AND p.y=c.y AND p.z=c.z WHERE c.build_id=? AND p.build_id<>c.build_id LIMIT 1")) {
            s.setString(1,wall.world());s.setLong(2,wall.id());try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("На месте стены зарегистрирована другая постройка.");}
        }
    }
    public static void completeUndo(Connection db,WallStore.Job job,long now)throws SQLException {
        tx(db);if(!job.kind().equals("UNDO"))throw new SQLException("Not a wall undo job");
        long refund;try(var s=db.prepareStatement("SELECT state,refund FROM wall_jobs WHERE id=? AND build_id=? AND kind='UNDO'")) {
            s.setString(1,job.id());s.setLong(2,job.build());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing wall undo job");if(r.getString(1).equals("DONE"))return;refund=r.getLong(2);}
        }
        var wall=wall(db,job.build());if(!wall.state().equals("REMOVING") || !wall.status().equals("WALL_REMOVE_PENDING"))throw new SQLException("Wall state changed during undo");requireOwnership(db,wall);
        retire(db,wall,now);
        try(var s=db.prepareStatement("""
            SELECT c.x,c.y,c.z,c.before_data,c.source_build FROM wall_cells c JOIN structure_builds b ON b.id=c.source_build
            WHERE c.build_id=? AND b.status NOT IN ('DEMOLISHED','CANCELLED')
            """);var insert=db.prepareStatement("INSERT INTO protected_blocks(world,x,y,z,build_id,material) VALUES(?,?,?,?,?,?)")) {
            s.setLong(1,wall.id());try(var r=s.executeQuery()){while(r.next()) {
                insert.setString(1,wall.world());insert.setInt(2,r.getInt(1));insert.setInt(3,r.getInt(2));insert.setInt(4,r.getInt(3));insert.setLong(5,r.getLong(5));insert.setString(6,WallUndoRules.material(r.getString(4)).toUpperCase(Locale.ROOT));insert.addBatch();
            }}insert.executeBatch();
        }
        if(refund>0)try(var s=db.prepareStatement("UPDATE towns SET coins=coins+? WHERE id=? AND coins<=?")) {
            s.setLong(1,refund);s.setLong(2,wall.town());s.setLong(3,Long.MAX_VALUE-refund);if(s.executeUpdate()!=1)throw new SQLException("Wall refund treasury overflow or missing town");
        }
        try(var s=db.prepareStatement("UPDATE wall_jobs SET state='DONE' WHERE id=? AND state='PREPARED'")){s.setString(1,job.id());if(s.executeUpdate()!=1)throw new SQLException("Wall undo completion changed");}
    }
    private static void retire(Connection db,Wall wall,long now)throws SQLException {
        for(String table:List.of("protected_blocks","wall_columns"))try(var s=db.prepareStatement("DELETE FROM "+table+" WHERE build_id=?")){s.setLong(1,wall.id());s.executeUpdate();}
        try(var s=db.prepareStatement("DELETE FROM wall_undo_targets WHERE build_id=?")){s.setLong(1,wall.id());s.executeUpdate();}
        try(var s=db.prepareStatement("UPDATE walls SET state='RETIRED' WHERE build_id=?")){s.setLong(1,wall.id());if(s.executeUpdate()!=1)throw new SQLException("Wall retirement changed");}
        try(var s=db.prepareStatement("UPDATE structure_builds SET status='DEMOLISHED',completed_at=? WHERE id=?")){s.setLong(1,now);s.setLong(2,wall.id());if(s.executeUpdate()!=1)throw new SQLException("Wall build retirement changed");}
    }
}
