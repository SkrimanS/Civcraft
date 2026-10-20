package com.avrgaming.civcraft.modern.wall;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;

/** Durable wall build intent. No world changes are made by this class. */
public final class WallStore {
    private WallStore() {}
    public record Job(String id,long build,String world,String kind,String state) {}
    public record Image(WallRules.Point point,String data,String nbt) {}
    public record Position(String world,int x,int y,int z) {}
    private static void tx(Connection db)throws SQLException{if(db.getAutoCommit())throw new SQLException("Wall operation requires a transaction");}
    public static boolean installed(Connection db)throws SQLException {
        try(var s=db.createStatement();var r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='walls'")){return r.next();}
    }
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS walls(
                  build_id INTEGER PRIMARY KEY REFERENCES structure_builds(id) ON DELETE CASCADE,
                  request_id TEXT NOT NULL UNIQUE,draft_hash TEXT NOT NULL,
                  state TEXT NOT NULL CHECK(state IN ('BUILDING','ACTIVE','REMOVING','RETIRED')),
                  maximum_height INTEGER NOT NULL,wall_height INTEGER NOT NULL,segment_cost REAL NOT NULL,
                  segments INTEGER NOT NULL,endpoint_included INTEGER NOT NULL,built_at INTEGER NOT NULL)
                """);
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS wall_cells(
                  build_id INTEGER NOT NULL REFERENCES walls(build_id) ON DELETE CASCADE,ordinal INTEGER NOT NULL,
                  x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,before_data TEXT NOT NULL,before_nbt TEXT,
                  after_data TEXT NOT NULL,source_build INTEGER,PRIMARY KEY(build_id,x,y,z),UNIQUE(build_id,ordinal))
                """);
            s.executeUpdate("CREATE TABLE IF NOT EXISTS wall_columns(build_id INTEGER NOT NULL REFERENCES walls(build_id) ON DELETE CASCADE,x INTEGER NOT NULL,z INTEGER NOT NULL,PRIMARY KEY(build_id,x,z))");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS wall_column_position ON wall_columns(x,z)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS wall_jobs(id TEXT PRIMARY KEY,build_id INTEGER NOT NULL REFERENCES walls(build_id) ON DELETE CASCADE,kind TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('PREPARED','DONE')))");
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_pending_wall_job ON wall_jobs(build_id) WHERE state='PREPARED'");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS wall_job_cells(job_id TEXT NOT NULL REFERENCES wall_jobs(id) ON DELETE CASCADE,ordinal INTEGER NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,block_data TEXT NOT NULL,block_nbt TEXT,PRIMARY KEY(job_id,ordinal),UNIQUE(job_id,x,y,z))");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS wall_job_cell_position ON wall_job_cells(x,y,z)");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS pending_wall_blocks_delete BEFORE DELETE ON structure_builds WHEN EXISTS(SELECT 1 FROM wall_jobs WHERE build_id=OLD.id AND state='PREPARED') BEGIN SELECT RAISE(ABORT,'Wall terrain operation is pending'); END");
        }
    }
    public static long prepare(Connection db,String request,UUID actor,WallPlacement.Plan plan,long now)throws SQLException {
        tx(db);
        if(request==null || request.isBlank() || plan.cells().isEmpty())throw new IllegalArgumentException("Неверный план строительства стены.");
        String fingerprint=fingerprint(actor,plan);
        try(var s=db.prepareStatement("SELECT build_id,draft_hash FROM walls WHERE request_id=?")) {
            s.setString(1,request);try(var r=s.executeQuery()){if(r.next()){if(!r.getString(2).equals(fingerprint))throw new SQLException("Wall request reused with a different draft");return r.getLong(1);}}
        }
        var owner=WallPlacement.authority(db,actor,plan.profile(),true);
        if(!owner.equals(plan.owner()))throw new IllegalArgumentException("Город или цивилизация изменились после разметки стены.");
        double calculated=WallRules.cost(plan.route(),plan.profile(),plan.cells().size(),plan.endpointIncluded());
        if(Double.compare(calculated,plan.cost())!=0)throw new SQLException("Wall draft price does not match geometry");
        long cost=Math.round(calculated);
        var positions=new ArrayList<com.avrgaming.civcraft.modern.trade.GoodieChestStore.Position>();
        var columns=new HashSet<WallRules.Point>();
        for(var cell:plan.cells()) {
            var p=cell.point();if(columns.add(new WallRules.Point(p.x(),0,p.z())))requireColumnsFree(db,plan.world(),p.x(),p.z());
            if(reserved(db,plan.world(),p.x(),p.y(),p.z()))throw new IllegalArgumentException("На месте стены уже выполняется другая работа со стеной.");
            positions.add(new com.avrgaming.civcraft.modern.trade.GoodieChestStore.Position(plan.world(),p.x(),p.y(),p.z()));
        }
        com.avrgaming.civcraft.modern.trade.GoodieChestStore.requirePositionsAvailable(db,positions);
        try(var s=db.prepareStatement("UPDATE towns SET coins=coins-? WHERE id=? AND coins>=?")) {
            s.setLong(1,cost);s.setLong(2,owner.town());s.setLong(3,cost);if(s.executeUpdate()!=1)throw new IllegalArgumentException("В казне города недостаточно монет: стена стоит "+cost+".");
        }
        long build;var origin=plan.route().samples().getFirst();
        try(var s=db.prepareStatement("""
            INSERT INTO structure_builds(builder_uuid,town_id,type,template,direction,world,x,y,z,template_path,
              queued_blocks,structure_id,display_name,cost,max_hitpoints,status,created_at,started_at,finish_at,snapshot_ready,journal_started)
            VALUES(?,?,'structures','notused','north',?,?,?,?,'wall.notused',?,?,?,?,?,'WALL_PREPARED',?,?,?,1,1)
            """,Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1,actor.toString());s.setLong(2,owner.town());s.setString(3,plan.world());s.setInt(4,origin.x());s.setInt(5,origin.y());s.setInt(6,origin.z());
            s.setInt(7,plan.cells().size());s.setString(8,plan.profile().type());s.setString(9,plan.profile().fortified()?"Крепостная стена":"Стена");s.setLong(10,cost);s.setInt(11,plan.profile().hitpoints());
            s.setLong(12,now);s.setLong(13,now);s.setLong(14,now);s.executeUpdate();try(var r=s.getGeneratedKeys()){if(!r.next())throw new SQLException("Missing new wall ID");build=r.getLong(1);}
        }
        try(var s=db.prepareStatement("INSERT INTO walls VALUES(?,?,?,'BUILDING',?,?,?,?,?,?)")) {
            s.setLong(1,build);s.setString(2,request);s.setString(3,fingerprint);s.setInt(4,plan.profile().maximumHeight());s.setInt(5,plan.profile().height());s.setDouble(6,plan.profile().segmentCost());
            s.setInt(7,plan.route().segments(plan.endpointIncluded()));s.setBoolean(8,plan.endpointIncluded());s.setLong(9,now);s.executeUpdate();
        }
        try(var s=db.prepareStatement("INSERT INTO wall_cells VALUES(?,?,?,?,?,?,?,?,?)");var column=db.prepareStatement("INSERT OR IGNORE INTO wall_columns VALUES(?,?,?)")) {
            int ordinal=0;for(var cell:plan.cells()) {
                var p=cell.point();s.setLong(1,build);s.setInt(2,ordinal++);s.setInt(3,p.x());s.setInt(4,p.y());s.setInt(5,p.z());s.setString(6,cell.before());s.setString(7,cell.nbt());s.setString(8,cell.after());
                if(cell.displacedBuild()==null)s.setNull(9,Types.INTEGER);else s.setLong(9,cell.displacedBuild());s.addBatch();
                column.setLong(1,build);column.setInt(2,p.x());column.setInt(3,p.z());column.addBatch();
            }s.executeBatch();column.executeBatch();
        }
        String job="wall-build:"+request;
        try(var s=db.prepareStatement("INSERT INTO wall_jobs VALUES(?,?,'BUILD','PREPARED')")){s.setString(1,job);s.setLong(2,build);s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO wall_job_cells SELECT ?,ordinal,x,y,z,after_data,NULL FROM wall_cells WHERE build_id=?")){s.setString(1,job);s.setLong(2,build);s.executeUpdate();}
        return build;
    }
    public static boolean reserved(Connection db,String world,int x,int y,int z)throws SQLException {
        if(!installed(db))return false;
        try(var s=db.prepareStatement("SELECT 1 FROM wall_job_cells c JOIN wall_jobs j ON j.id=c.job_id JOIN structure_builds b ON b.id=j.build_id WHERE j.state='PREPARED' AND b.world=? AND c.x=? AND c.y=? AND c.z=? LIMIT 1")) {
            s.setString(1,world);s.setInt(2,x);s.setInt(3,y);s.setInt(4,z);try(var r=s.executeQuery()){return r.next();}
        }
    }
    public static Set<Position> reservedPositions(Connection db)throws SQLException {
        if(!installed(db))return Set.of();var result=new HashSet<Position>();
        try(var s=db.createStatement();var r=s.executeQuery("SELECT b.world,c.x,c.y,c.z FROM wall_job_cells c JOIN wall_jobs j ON j.id=c.job_id JOIN structure_builds b ON b.id=j.build_id WHERE j.state='PREPARED'")) {
            while(r.next())result.add(new Position(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4)));
        }return Set.copyOf(result);
    }
    public static boolean occupiesChunk(Connection db,String world,int chunkX,int chunkZ)throws SQLException {
        if(!installed(db))return false;
        try(var s=db.prepareStatement("SELECT 1 FROM wall_columns c JOIN walls w ON w.build_id=c.build_id JOIN structure_builds b ON b.id=w.build_id WHERE b.world=? AND c.x BETWEEN ? AND ? AND c.z BETWEEN ? AND ? AND w.state<>'RETIRED' LIMIT 1")) {
            s.setString(1,world);s.setLong(2,(long)chunkX*16);s.setLong(3,(long)chunkX*16+15);s.setLong(4,(long)chunkZ*16);s.setLong(5,(long)chunkZ*16+15);try(var r=s.executeQuery()){return r.next();}
        }
    }
    /** Operational locks only. Completed wall column protection is a separate rule. */
    public static void requireAreaFree(Connection db,String world,int x,int y,int z,int width,int height,int depth)throws SQLException {
        if(!installed(db))return;
        if(width<1 || height<1 || depth<1)throw new IllegalArgumentException("Неверный размер проверяемой области стены.");
        try(var s=db.prepareStatement("""
            SELECT 1 FROM wall_job_cells c JOIN wall_jobs j ON j.id=c.job_id JOIN structure_builds b ON b.id=j.build_id
            WHERE j.state='PREPARED' AND b.world=? AND c.x BETWEEN ? AND ? AND c.y BETWEEN ? AND ? AND c.z BETWEEN ? AND ? LIMIT 1
            """)) {
            s.setString(1,world);s.setInt(2,x);s.setInt(3,Math.addExact(x,width-1));s.setInt(4,y);s.setInt(5,Math.addExact(y,height-1));s.setInt(6,z);s.setInt(7,Math.addExact(z,depth-1));
            try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("В этой области ещё выполняется установка или восстановление стены.");}
        }
    }
    public static void requireColumnsFree(Connection db,String world,int x,int z)throws SQLException {
        if(!installed(db))return;
        try(var s=db.prepareStatement("""
            SELECT 1 FROM wall_columns c JOIN walls w ON w.build_id=c.build_id JOIN structure_builds b ON b.id=w.build_id
            WHERE b.world=? AND c.x=? AND c.z=? AND w.state<>'RETIRED' AND (w.state<>'ACTIVE'
            OR EXISTS(SELECT 1 FROM wall_jobs j WHERE j.build_id=b.id AND j.state='PREPARED') OR EXISTS(
              SELECT 1 FROM protected_blocks p WHERE p.build_id=b.id AND p.world=b.world AND p.x=c.x AND p.z=c.z)) LIMIT 1
            """)) {
            s.setString(1,world);s.setInt(2,x);s.setInt(3,z);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("В колонке уже находится стена или незавершённая работа с ней.");}
        }
    }
    public static List<Job> pending(Connection db)throws SQLException {
        var out=new ArrayList<Job>();try(var s=db.createStatement();var r=s.executeQuery("SELECT j.*,b.world FROM wall_jobs j JOIN structure_builds b ON b.id=j.build_id WHERE j.state='PREPARED' ORDER BY j.rowid")){while(r.next())out.add(new Job(r.getString("id"),r.getLong("build_id"),r.getString("world"),r.getString("kind"),r.getString("state")));}return List.copyOf(out);
    }
    public static Long request(Connection db,String request,UUID actor)throws SQLException {
        try(var s=db.prepareStatement("SELECT w.build_id,b.builder_uuid FROM walls w JOIN structure_builds b ON b.id=w.build_id WHERE w.request_id=?")) {
            s.setString(1,request);try(var r=s.executeQuery()){if(!r.next())return null;if(!actor.toString().equals(r.getString(2)))throw new SQLException("Wall request belongs to another actor");return r.getLong(1);}
        }
    }
    public static boolean pending(Connection db,Job job)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM wall_jobs WHERE id=? AND build_id=? AND state='PREPARED'")){s.setString(1,job.id());s.setLong(2,job.build());try(var r=s.executeQuery()){return r.next();}}
    }
    public static List<Image> images(Connection db,String job)throws SQLException {
        var out=new ArrayList<Image>();try(var s=db.prepareStatement("SELECT * FROM wall_job_cells WHERE job_id=? ORDER BY ordinal")) {
            s.setString(1,job);try(var r=s.executeQuery()){while(r.next())out.add(new Image(new WallRules.Point(r.getInt("x"),r.getInt("y"),r.getInt("z")),r.getString("block_data"),r.getString("block_nbt")));}
        }return List.copyOf(out);
    }
    /** Called only after the complete world after-image has been saved. */
    public static void completeBuild(Connection db,Job job,long now)throws SQLException {
        tx(db);if(!job.kind().equals("BUILD"))throw new SQLException("Unsupported wall completion kind");
        try(var s=db.prepareStatement("SELECT state FROM wall_jobs WHERE id=? AND build_id=? AND kind='BUILD'")) {
            s.setString(1,job.id());s.setLong(2,job.build());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing wall build job");if(r.getString(1).equals("DONE"))return;}
        }
        try(var s=db.prepareStatement("""
            SELECT 1 FROM wall_cells c JOIN protected_blocks p ON p.world=? AND p.x=c.x AND p.y=c.y AND p.z=c.z
            WHERE c.build_id=? AND p.build_id<>c.build_id AND (c.source_build IS NULL OR p.build_id<>c.source_build) LIMIT 1
            """)) {
            s.setString(1,job.world());s.setLong(2,job.build());try(var r=s.executeQuery()){if(r.next())throw new SQLException("Wall protection ownership changed during world write");}
        }
        try(var read=db.prepareStatement("SELECT x,y,z,after_data FROM wall_cells WHERE build_id=?");
            var insert=db.prepareStatement("INSERT INTO protected_blocks(world,x,y,z,build_id,material) VALUES(?,?,?,?,?,?) ON CONFLICT(world,x,y,z) DO UPDATE SET build_id=excluded.build_id,material=excluded.material")) {
            read.setLong(1,job.build());try(var r=read.executeQuery()){while(r.next()) {
                String data=r.getString(4);int state=data.indexOf('[');if(state>=0)data=data.substring(0,state);
                String material=data.substring(data.indexOf(':')+1).toUpperCase(Locale.ROOT);
                insert.setString(1,job.world());insert.setInt(2,r.getInt(1));insert.setInt(3,r.getInt(2));insert.setInt(4,r.getInt(3));insert.setLong(5,job.build());insert.setString(6,material);insert.addBatch();
            }}insert.executeBatch();
        }
        try(var s=db.prepareStatement("UPDATE structure_builds SET status='COMPLETE',completed_at=? WHERE id=? AND status='WALL_PREPARED'")){s.setLong(1,now);s.setLong(2,job.build());if(s.executeUpdate()!=1)throw new SQLException("Wall build status changed");}
        try(var s=db.prepareStatement("UPDATE walls SET state='ACTIVE' WHERE build_id=? AND state='BUILDING'")){s.setLong(1,job.build());if(s.executeUpdate()!=1)throw new SQLException("Wall state changed");}
        try(var s=db.prepareStatement("UPDATE wall_jobs SET state='DONE' WHERE id=? AND state='PREPARED'")){s.setString(1,job.id());if(s.executeUpdate()!=1)throw new SQLException("Wall job state changed");}
    }
    private static String fingerprint(UUID actor,WallPlacement.Plan plan)throws SQLException {
        try {
            var hash=MessageDigest.getInstance("SHA-256");
            for(String value:List.of(actor.toString(),plan.world(),plan.profile().toString(),plan.owner().toString(),plan.route().toString(),Boolean.toString(plan.endpointIncluded()),Double.toHexString(plan.cost())))part(hash,value);
            for(var cell:plan.cells()){part(hash,cell.point().toString());part(hash,cell.before());part(hash,cell.nbt());part(hash,cell.after());part(hash,Objects.toString(cell.displacedBuild(),null));}
            return HexFormat.of().formatHex(hash.digest());
        }catch(NoSuchAlgorithmException failure){throw new SQLException("Cannot hash wall draft",failure);}
    }
    private static void part(MessageDigest hash,String value) {
        if(value==null){hash.update(new byte[]{-1,-1,-1,-1});return;}byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        hash.update(new byte[]{(byte)(bytes.length>>>24),(byte)(bytes.length>>>16),(byte)(bytes.length>>>8),(byte)bytes.length});hash.update(bytes);
    }
}
