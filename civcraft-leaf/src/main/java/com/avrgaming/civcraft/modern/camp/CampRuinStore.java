package com.avrgaming.civcraft.modern.camp;

import com.avrgaming.civcraft.modern.town.ConsumptionContainers.Position;
import com.avrgaming.civcraft.modern.trade.GoodieChestStore;
import java.sql.*;
import java.util.*;

/** Immutable random outcome survives until all world changes and camp removal are committed. */
public final class CampRuinStore {
    private CampRuinStore() {}
    public record Cell(Position position,String before,String after,boolean effect,String afterNbt) {
        public Cell(Position position,String before,String after,boolean effect){this(position,before,after,effect,null);}
    }
    public record Pending(long camp,long build,UUID leader,boolean title,List<Cell> cells,List<UUID> members,boolean restore) {
        public Pending{cells=List.copyOf(cells);members=List.copyOf(members);}
        public Pending(long camp,long build,UUID leader,boolean title,List<Cell> cells,List<UUID> members){this(camp,build,leader,title,cells,members,false);}
    }
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS camp_ruins(camp_id INTEGER PRIMARY KEY,build_id INTEGER NOT NULL,leader_uuid TEXT NOT NULL,show_title INTEGER NOT NULL,status TEXT NOT NULL DEFAULT 'PREPARED',created_at INTEGER NOT NULL)");
            boolean mode=false;try(var r=s.executeQuery("PRAGMA table_info(camp_ruins)")){while(r.next())if(r.getString("name").equals("mode"))mode=true;}
            if(!mode)s.executeUpdate("ALTER TABLE camp_ruins ADD COLUMN mode TEXT NOT NULL DEFAULT 'RUIN'");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS camp_ruin_cells(camp_id INTEGER NOT NULL REFERENCES camp_ruins(camp_id) ON DELETE CASCADE,world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,before_state TEXT NOT NULL,after_state TEXT,effect INTEGER NOT NULL,PRIMARY KEY(camp_id,world,x,y,z))");
            boolean nbt=false;try(var r=s.executeQuery("PRAGMA table_info(camp_ruin_cells)")){while(r.next())if(r.getString("name").equals("after_nbt"))nbt=true;}
            if(!nbt)s.executeUpdate("ALTER TABLE camp_ruin_cells ADD COLUMN after_nbt TEXT");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS camp_ruin_cell_position ON camp_ruin_cells(world,x,y,z)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS camp_ruin_members(camp_id INTEGER NOT NULL REFERENCES camp_ruins(camp_id) ON DELETE CASCADE,uuid TEXT NOT NULL,PRIMARY KEY(camp_id,uuid))");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS camp_ruin_blocks_delete BEFORE DELETE ON camps WHEN EXISTS(SELECT 1 FROM camp_ruins WHERE camp_id=OLD.id AND status='PREPARED') BEGIN SELECT RAISE(ABORT,'Camp ruin is pending'); END");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS camp_ruin_blocks_owner_change BEFORE UPDATE OF owner_uuid ON camps WHEN NEW.owner_uuid<>OLD.owner_uuid AND EXISTS(SELECT 1 FROM camp_ruins WHERE camp_id=OLD.id AND status='PREPARED') BEGIN SELECT RAISE(ABORT,'Camp ruin is pending'); END");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS camp_ruin_blocks_build_delete BEFORE DELETE ON structure_builds WHEN EXISTS(SELECT 1 FROM camp_ruins WHERE build_id=OLD.id AND status='PREPARED') BEGIN SELECT RAISE(ABORT,'Camp ruin is pending'); END");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS camp_ruin_blocks_build_change BEFORE UPDATE OF status ON structure_builds WHEN NEW.status<>OLD.status AND EXISTS(SELECT 1 FROM camp_ruins WHERE build_id=OLD.id AND status='PREPARED') BEGIN SELECT RAISE(ABORT,'Camp ruin is pending'); END");
        }
    }
    public static void prepare(Connection db,Pending p)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Camp ruin requires a transaction");
        CampLonghouseStore.requireUnlocked(db,p.camp());
        GoodieChestStore.requirePositionsAvailable(db,p.cells().stream().map(c->new GoodieChestStore.Position(c.position().world(),c.position().x(),c.position().y(),c.position().z())).toList());
        try(var s=db.prepareStatement("SELECT b.status,b.journal_started,b.snapshot_ready FROM camps c JOIN structure_builds b ON b.id=? AND b.world=c.world AND b.x=c.x AND b.y=c.y AND b.z=c.z WHERE c.id=? AND c.owner_uuid=?")) {
            s.setLong(1,p.build());s.setLong(2,p.camp());s.setString(3,p.leader().toString());try(var r=s.executeQuery()){
                if(!r.next() || !(p.restore()?Set.of("COMPLETE","QUEUED","BUILDING").contains(r.getString(1)):r.getString(1).equals("COMPLETE")))throw new SQLException("Camp changed before removal planning");
                if(p.restore() && (r.getInt(2)==0 || (r.getInt(3)!=0 && p.cells().isEmpty()) || (r.getString(1).equals("COMPLETE") && r.getInt(3)==0)))throw new SQLException("Camp terrain journal is incomplete");
            }
        }
        if(p.restore())try(var s=db.prepareStatement("UPDATE structure_builds SET status='CAMP_RESTORE_PENDING' WHERE id=?")){s.setLong(1,p.build());s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO camp_ruins(camp_id,build_id,leader_uuid,show_title,created_at,mode) VALUES(?,?,?,?,?,?)")){s.setLong(1,p.camp());s.setLong(2,p.build());s.setString(3,p.leader().toString());s.setInt(4,p.title()?1:0);s.setLong(5,System.currentTimeMillis());s.setString(6,p.restore()?"RESTORE":"RUIN");s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO camp_ruin_cells(camp_id,world,x,y,z,before_state,after_state,effect,after_nbt) VALUES(?,?,?,?,?,?,?,?,?)")) {
            var seen=new HashSet<Position>();for(var cell:p.cells()){var pos=cell.position();if(!seen.add(pos))throw new SQLException("Duplicate camp ruin cell");s.setLong(1,p.camp());s.setString(2,pos.world());s.setInt(3,pos.x());s.setInt(4,pos.y());s.setInt(5,pos.z());s.setString(6,cell.before());s.setString(7,cell.after());s.setInt(8,cell.effect()?1:0);s.setString(9,cell.afterNbt());s.addBatch();}s.executeBatch();
        }
        try(var s=db.prepareStatement("INSERT INTO camp_ruin_members VALUES(?,?)")){for(var member:p.members()){s.setLong(1,p.camp());s.setString(2,member.toString());s.addBatch();}s.executeBatch();}
    }
    public static List<Pending> pending(Connection db)throws SQLException {
        var out=new ArrayList<Pending>();try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM camp_ruins WHERE status='PREPARED' ORDER BY created_at,camp_id")) {
            while(r.next()){long camp=r.getLong("camp_id");out.add(new Pending(camp,r.getLong("build_id"),UUID.fromString(r.getString("leader_uuid")),r.getInt("show_title")!=0,cells(db,camp),members(db,camp),r.getString("mode").equals("RESTORE")));}
        }return List.copyOf(out);
    }
    private static List<Cell> cells(Connection db,long camp)throws SQLException {
        var out=new ArrayList<Cell>();try(var s=db.prepareStatement("SELECT * FROM camp_ruin_cells WHERE camp_id=? ORDER BY world,x,y,z")){s.setLong(1,camp);try(var r=s.executeQuery()){while(r.next())out.add(new Cell(new Position(r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z")),r.getString("before_state"),r.getString("after_state"),r.getInt("effect")!=0,r.getString("after_nbt")));}}return out;
    }
    private static List<UUID> members(Connection db,long camp)throws SQLException {
        var out=new ArrayList<UUID>();try(var s=db.prepareStatement("SELECT uuid FROM camp_ruin_members WHERE camp_id=?")){s.setLong(1,camp);try(var r=s.executeQuery()){while(r.next())out.add(UUID.fromString(r.getString(1)));}}return out;
    }
    public static boolean complete(Connection db,Pending p)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Camp ruin requires a transaction");
        try(var s=db.prepareStatement("UPDATE camp_ruins SET status='COMPLETED' WHERE camp_id=? AND status='PREPARED'")){s.setLong(1,p.camp());if(s.executeUpdate()==0)return false;}
        try(var s=db.prepareStatement("UPDATE structure_builds SET status='DEMOLISHED',completed_at=? WHERE id=? AND status=?")){s.setLong(1,System.currentTimeMillis());s.setLong(2,p.build());s.setString(3,p.restore()?"CAMP_RESTORE_PENDING":"COMPLETE");if(s.executeUpdate()!=1)throw new SQLException("Camp structure changed during removal recovery");}
        for(String table:List.of("protected_blocks","structure_chunks","structure_block_backups","structure_chests"))try(var s=db.prepareStatement("DELETE FROM "+table+" WHERE build_id=?")){s.setLong(1,p.build());s.executeUpdate();}
        try(var s=db.prepareStatement("UPDATE residents SET camp_id=NULL,updated_at=? WHERE camp_id=?")){s.setLong(1,System.currentTimeMillis());s.setLong(2,p.camp());s.executeUpdate();}
        try(var s=db.prepareStatement("DELETE FROM camp_members WHERE camp_id=?")){s.setLong(1,p.camp());s.executeUpdate();}
        try(var s=db.prepareStatement("DELETE FROM camps WHERE id=? AND owner_uuid=?")){s.setLong(1,p.camp());s.setString(2,p.leader().toString());if(s.executeUpdate()!=1)throw new SQLException("Camp changed during ruin recovery");}
        try(var s=db.prepareStatement("DELETE FROM camp_ruin_cells WHERE camp_id=?")){s.setLong(1,p.camp());s.executeUpdate();}
        return true;
    }
}
