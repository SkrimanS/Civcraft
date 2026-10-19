package com.avrgaming.civcraft.modern.trade;

import java.io.*;
import java.sql.*;
import java.util.*;

/** One atomic intent can swap multiple goods between physical chest halves, player and cursor. */
public final class GoodieChestStore {
    private GoodieChestStore() {}
    public record Position(String world,int x,int y,int z) {
        public Position{if(world==null || world.isBlank())throw new IllegalArgumentException("Missing chest world");}
        public String holder(){return world.length()+":"+world+":"+x+":"+y+":"+z;}
    }
    public record Image(Position position,String material,byte[] before,byte[] after) {
        public Image {
            Objects.requireNonNull(position);if(!Set.of("CHEST","TRAPPED_CHEST").contains(material))throw new IllegalArgumentException("Only physical chests can hold trade goods");
            before=bounded(before);after=bounded(after);
        }
        @Override public byte[] before(){return before.clone();}
        @Override public byte[] after(){return after.clone();}
    }
    public record Move(BonusGoodieStore.State before,BonusGoodieStore.Kind kind,String holder,GoodieTransferStore.Drop drop) {
        public Move(BonusGoodieStore.State before,BonusGoodieStore.Kind kind,String holder){this(before,kind,holder,null);}
        public Move{Objects.requireNonNull(before);Objects.requireNonNull(kind);Objects.requireNonNull(holder);}
        public BonusGoodieStore.State after(){return new BonusGoodieStore.State(before.origin(),before.good(),before.token(),Math.addExact(before.revision(),1),kind,holder,null);}
    }
    public record Pending(String id,UUID player,byte[] inventory,byte[] cursor,List<Image> images,List<Move> moves,String cursorToken,Long cursorRevision) {
        public Pending{inventory=bounded(inventory);cursor=bounded(cursor);images=List.copyOf(images);moves=List.copyOf(moves);}
        @Override public byte[] inventory(){return inventory.clone();}
        @Override public byte[] cursor(){return cursor.clone();}
    }
    public record Cursor(String receipt,String token,long revision,byte[] item) {
        public Cursor{item=bounded(item);}
        @Override public byte[] item(){return item.clone();}
    }
    public record PlainCursor(String receipt,byte[] item,boolean clear){public PlainCursor{item=bounded(item);}@Override public byte[] item(){return item.clone();}}
    private static byte[] bounded(byte[] data){if(data==null || data.length<1 || data.length>16*1024*1024)throw new IllegalArgumentException("Invalid chest inventory image");return data.clone();}
    private static void transaction(Connection db)throws SQLException{if(db.getAutoCommit())throw new SQLException("Chest transfer requires a transaction");}
    public static void install(Connection db)throws SQLException {
        PlainTransferGround.install(db);
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS goodie_chest_transfers(id TEXT PRIMARY KEY,player_uuid TEXT NOT NULL,inventory BLOB NOT NULL,cursor BLOB NOT NULL,cursor_token TEXT,cursor_revision INTEGER,status TEXT NOT NULL CHECK(status IN ('PREPARED','COMPLETE')),created_at INTEGER NOT NULL)");
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_pending_goodie_chest_player ON goodie_chest_transfers(player_uuid) WHERE status='PREPARED'");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS goodie_chest_images(operation_id TEXT NOT NULL REFERENCES goodie_chest_transfers(id) ON DELETE CASCADE,world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,material TEXT NOT NULL,before_data BLOB NOT NULL,after_data BLOB NOT NULL,PRIMARY KEY(operation_id,world,x,y,z))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS goodie_chest_moves(operation_id TEXT NOT NULL REFERENCES goodie_chest_transfers(id) ON DELETE CASCADE,origin_build INTEGER NOT NULL,good_id TEXT NOT NULL,token TEXT NOT NULL,before_revision INTEGER NOT NULL,before_kind TEXT NOT NULL,before_holder TEXT NOT NULL,before_town INTEGER,after_kind TEXT NOT NULL,after_holder TEXT NOT NULL,PRIMARY KEY(operation_id,origin_build))");
            var columns=new HashSet<String>();try(var r=s.executeQuery("PRAGMA table_info(goodie_chest_moves)")){while(r.next())columns.add(r.getString("name"));}
            for(String column:List.of("drop_world","drop_x","drop_y","drop_z"))if(!columns.contains(column))s.executeUpdate("ALTER TABLE goodie_chest_moves ADD COLUMN "+column+(column.equals("drop_world")?" TEXT":" REAL"));
            s.executeUpdate("CREATE TABLE IF NOT EXISTS goodie_chest_cursor_checkpoints(player_uuid TEXT PRIMARY KEY,receipt_id TEXT NOT NULL,token TEXT NOT NULL,revision INTEGER NOT NULL,item BLOB NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS goodie_plain_cursor_deliveries(player_uuid TEXT PRIMARY KEY,receipt_id TEXT NOT NULL,item BLOB NOT NULL,status TEXT NOT NULL CHECK(status IN ('PREPARED','CLEAR','DELIVERED')))");
        }
    }
    public static boolean pendingPlayer(Connection db,UUID player)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM goodie_chest_transfers WHERE player_uuid=? AND status='PREPARED'")){s.setString(1,player.toString());try(var r=s.executeQuery()){return r.next();}}
    }
    public static void requireOriginUnlocked(Connection db,long origin)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM goodie_chest_moves m JOIN goodie_chest_transfers t ON t.id=m.operation_id WHERE t.status='PREPARED' AND m.origin_build=? LIMIT 1")){s.setLong(1,origin);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Товар участвует в незавершённом обмене с сундуком.");}}
    }
    public static Set<Position> locked(Connection db)throws SQLException {
        var out=new HashSet<Position>();try(var s=db.createStatement();var r=s.executeQuery("SELECT i.world,i.x,i.y,i.z FROM goodie_chest_images i JOIN goodie_chest_transfers t ON t.id=i.operation_id WHERE t.status='PREPARED'")){while(r.next())out.add(new Position(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4)));}return Set.copyOf(out);
    }
    private static boolean installed(Connection db)throws SQLException {
        try(var s=db.createStatement();var r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='goodie_chest_transfers'")){return r.next();}
    }
    public static void requireStructureUnlocked(Connection db,long build,boolean town)throws SQLException {
        if(!installed(db))return;
        try(var s=db.prepareStatement("SELECT 1 FROM goodie_chest_images i JOIN goodie_chest_transfers operation ON operation.id=i.operation_id JOIN structure_chests c ON c.world=i.world AND c.x=i.x AND c.y=i.y AND c.z=i.z JOIN structure_builds b ON b.id=c.build_id WHERE operation.status='PREPARED' AND "+(town?"b.town_id":"b.id")+"=? LIMIT 1")){s.setLong(1,build);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Сундуки здания участвуют в незавершённом переносе товара.");}}
    }
    public static void requireImagesUnlocked(Connection db,byte[] containers)throws SQLException {
        var reserved=installed(db)?new HashSet<>(locked(db)):new HashSet<Position>();
        for(String table:List.of("camp_longhouse_operations","camp_fire_operations")) {
            boolean exists;try(var s=db.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")){s.setString(1,table);try(var r=s.executeQuery()){exists=r.next();}}if(!exists)continue;
            try(var s=db.createStatement();var r=s.executeQuery("SELECT containers FROM "+table+" WHERE status='PREPARED'")){while(r.next())reserved.addAll(positions(r.getBytes(1)));}
        }
        reserved.addAll(ruinPositions(db));
        reserved.addAll(siegePositions(db));
        reserved.addAll(roadPositions(db));
        if(reserved.isEmpty())return;
        var positions=positions(containers);if(positions.stream().anyMatch(reserved::contains))throw new IllegalArgumentException("Сундук участвует в незавершённом переносе товара.");
    }
    private static Set<Position> positions(byte[] data)throws SQLException {
        var out=new HashSet<Position>();
        if(data==null || data.length>16*1024*1024)throw new SQLException("Invalid producer container image");
        try(var in=new DataInputStream(new ByteArrayInputStream(data))) {
            if(in.readInt()!=1)throw new IOException("Unknown producer container version");int count=in.readInt();if(count<0 || count>4096)throw new IOException("Invalid producer container count");
            for(int i=0;i<count;i++){if(!out.add(new Position(in.readUTF(),in.readInt(),in.readInt(),in.readInt())))throw new IOException("Duplicate producer container");int length=in.readInt();if(length<1 || length>in.available())throw new IOException("Invalid producer container image");in.skipNBytes(length);}if(in.available()!=0)throw new IOException("Trailing producer container data");
        }catch(IOException failure){throw new SQLException("Cannot validate producer container locks",failure);}return out;
    }
    /** Read only coordinates from the existing producer journals, without decoding Bukkit items. */
    private static Set<Position> producerPositions(Connection db)throws SQLException {
        var out=new HashSet<Position>(ruinPositions(db));
        out.addAll(siegePositions(db));
        out.addAll(roadPositions(db));
        for(String table:List.of("consumption_receipts","resource_production_operations","trade_ship_operations","camp_longhouse_operations","camp_fire_operations")) {
            boolean exists;try(var s=db.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")){s.setString(1,table);try(var r=s.executeQuery()){exists=r.next();}}if(!exists)continue;
            try(var s=db.createStatement();var r=s.executeQuery("SELECT containers FROM "+table+" WHERE status='PREPARED'")) {
                while(r.next())out.addAll(positions(r.getBytes(1)));
            }
        }return out;
    }
    private static Set<Position> ruinPositions(Connection db)throws SQLException {
        try(var s=db.createStatement();var r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='camp_ruins'")){if(!r.next())return Set.of();}
        var out=new HashSet<Position>();try(var s=db.createStatement();var r=s.executeQuery("SELECT c.world,c.x,c.y,c.z FROM camp_ruin_cells c JOIN camp_ruins job ON job.camp_id=c.camp_id WHERE job.status='PREPARED'")){while(r.next())out.add(new Position(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4)));}return out;
    }
    private static Set<Position> siegePositions(Connection db)throws SQLException {
        if(!com.avrgaming.civcraft.modern.war.SiegeWorldStore.installed(db))return Set.of();
        var out=new HashSet<Position>();try(var s=db.createStatement();var r=s.executeQuery("SELECT j.world,c.x,c.y,c.z FROM siege_world_cells c JOIN siege_world_jobs j ON j.id=c.job_id WHERE j.state='PREPARED'")){while(r.next())out.add(new Position(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4)));}
        try(var s=db.prepareStatement("SELECT t.world,t.x,t.y,t.z FROM siege_original_terrain t JOIN war_sessions w ON w.start_at=t.war_start WHERE t.block_nbt IS NOT NULL AND w.state='ACTIVE' AND w.end_at>?")){s.setLong(1,System.currentTimeMillis());try(var r=s.executeQuery()){while(r.next())out.add(new Position(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4)));}}return out;
    }
    private static Set<Position> roadPositions(Connection db)throws SQLException {
        if(!com.avrgaming.civcraft.modern.road.RoadStore.installed(db))return Set.of();
        var out=new HashSet<Position>();try(var s=db.createStatement();var r=s.executeQuery("SELECT b.world,c.x,c.y,c.z FROM road_job_cells c JOIN road_jobs j ON j.id=c.job_id JOIN structure_builds b ON b.id=j.build_id WHERE j.state='PREPARED'")){while(r.next())out.add(new Position(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4)));}return out;
    }
    public static void requirePositionsAvailable(Connection db,Collection<Position> positions)throws SQLException {
        var reserved=locked(db);var producers=producerPositions(db);if(positions.stream().anyMatch(p->reserved.contains(p) || producers.contains(p)))throw new IllegalArgumentException("Сундук участвует в незавершённой операции.");
    }
    public static Pending prepare(Connection db,String id,UUID player,byte[] inventory,byte[] cursor,List<Image> images,List<Move> moves,String cursorToken,Long cursorRevision,long now)throws SQLException {
        transaction(db);if(id==null || id.isBlank() || player==null || now<0 || images.size()>2 || moves.size()>128)throw new IllegalArgumentException("Invalid chest transfer");
        if((cursorToken==null)!=(cursorRevision==null))throw new IllegalArgumentException("Incomplete cursor checkpoint");
        var pending=new Pending(id,player,inventory,cursor,images,moves,cursorToken,cursorRevision);
        try(var s=db.prepareStatement("SELECT 1 FROM trade_receipts WHERE player_uuid=? AND status='PREPARED' UNION ALL SELECT 1 FROM goodie_transfers WHERE player_uuid=? AND status='PREPARED' UNION ALL SELECT 1 FROM goodie_chest_transfers WHERE player_uuid=? AND status='PREPARED'")){for(int i=1;i<=3;i++)s.setString(i,player.toString());try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Сначала завершите предыдущий обмен.");}}
        var occupied=locked(db);var producers=producerPositions(db);var positions=new HashSet<Position>();var holders=new HashSet<String>();
        for(var image:images){if(!positions.add(image.position()) || occupied.contains(image.position()) || producers.contains(image.position()))throw new IllegalArgumentException("Сундук участвует в незавершённой операции.");holders.add(image.position().holder());}
        var origins=new HashSet<Long>();
        for(var move:moves) {
            var before=move.before();if(!origins.add(before.origin()) || !before.equals(BonusGoodieStore.origin(db,before.origin())))throw new IllegalArgumentException("Торговый товар уже перемещён.");
            GoodieTransferStore.requireOriginUnlocked(db,before.origin());
            for(boolean after:List.of(false,true)) {
                var kind=after?move.kind():before.kind();var holder=after?move.holder():before.holder();
                if(kind==BonusGoodieStore.Kind.PLAYER){if(!holder.equals(player.toString()))throw new IllegalArgumentException("Товар находится у другого игрока.");}
                else if(after && kind==BonusGoodieStore.Kind.ITEM && move.drop()!=null && holder.equals(id+":"+before.origin())){}
                else if(kind!=BonusGoodieStore.Kind.CONTAINER || !holders.contains(holder))throw new IllegalArgumentException("Товар должен находиться в открытом сундуке или у игрока.");
            }
            if((move.kind()==BonusGoodieStore.Kind.ITEM)!=(move.drop()!=null))throw new IllegalArgumentException("Invalid chest item drop");
            move.after(); // Validate revision overflow before any intent is inserted.
        }
        if(cursorToken!=null && moves.stream().noneMatch(m->m.before().token().equals(cursorToken) && m.after().revision()==cursorRevision && m.kind()==BonusGoodieStore.Kind.PLAYER))throw new IllegalArgumentException("Cursor checkpoint does not match the transferred good");
        try(var s=db.prepareStatement("INSERT INTO goodie_chest_transfers VALUES(?,?,?,?,?,?,'PREPARED',?)")){s.setString(1,id);s.setString(2,player.toString());s.setBytes(3,inventory);s.setBytes(4,cursor);s.setString(5,cursorToken);if(cursorRevision==null)s.setNull(6,Types.BIGINT);else s.setLong(6,cursorRevision);s.setLong(7,now);s.executeUpdate();}
        for(var image:images)try(var s=db.prepareStatement("INSERT INTO goodie_chest_images VALUES(?,?,?,?,?,?,?,?)")){s.setString(1,id);s.setString(2,image.position().world());s.setInt(3,image.position().x());s.setInt(4,image.position().y());s.setInt(5,image.position().z());s.setString(6,image.material());s.setBytes(7,image.before());s.setBytes(8,image.after());s.executeUpdate();}
        for(var move:moves)try(var s=db.prepareStatement("INSERT INTO goodie_chest_moves VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")){var b=move.before();s.setString(1,id);s.setLong(2,b.origin());s.setString(3,b.good());s.setString(4,b.token());s.setLong(5,b.revision());s.setString(6,b.kind().name());s.setString(7,b.holder());if(b.hostTown()==null)s.setNull(8,Types.BIGINT);else s.setLong(8,b.hostTown());s.setString(9,move.kind().name());s.setString(10,move.holder());s.setString(11,move.drop()==null?null:move.drop().world());if(move.drop()==null){s.setNull(12,Types.DOUBLE);s.setNull(13,Types.DOUBLE);s.setNull(14,Types.DOUBLE);}else{s.setDouble(12,move.drop().x());s.setDouble(13,move.drop().y());s.setDouble(14,move.drop().z());}s.executeUpdate();}
        return pending;
    }
    public static List<Pending> pending(Connection db)throws SQLException {
        var ids=new ArrayList<String>();try(var s=db.createStatement();var r=s.executeQuery("SELECT id FROM goodie_chest_transfers WHERE status='PREPARED' ORDER BY created_at,id")){while(r.next())ids.add(r.getString(1));}
        var out=new ArrayList<Pending>();for(String id:ids)out.add(read(db,id));return out;
    }
    private static Pending read(Connection db,String id)throws SQLException {
        var images=new ArrayList<Image>();try(var s=db.prepareStatement("SELECT * FROM goodie_chest_images WHERE operation_id=? ORDER BY world,x,y,z")){s.setString(1,id);try(var r=s.executeQuery()){while(r.next())images.add(new Image(new Position(r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z")),r.getString("material"),r.getBytes("before_data"),r.getBytes("after_data")));}}
        var moves=new ArrayList<Move>();try(var s=db.prepareStatement("SELECT * FROM goodie_chest_moves WHERE operation_id=? ORDER BY origin_build")){s.setString(1,id);try(var r=s.executeQuery()){while(r.next()){var before=new BonusGoodieStore.State(r.getLong("origin_build"),r.getString("good_id"),r.getString("token"),r.getLong("before_revision"),BonusGoodieStore.Kind.valueOf(r.getString("before_kind")),r.getString("before_holder"),r.getObject("before_town")==null?null:r.getLong("before_town"));moves.add(new Move(before,BonusGoodieStore.Kind.valueOf(r.getString("after_kind")),r.getString("after_holder"),r.getString("drop_world")==null?null:new GoodieTransferStore.Drop(r.getString("drop_world"),r.getDouble("drop_x"),r.getDouble("drop_y"),r.getDouble("drop_z"))));}}}
        try(var s=db.prepareStatement("SELECT * FROM goodie_chest_transfers WHERE id=? AND status='PREPARED'")){s.setString(1,id);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Chest transfer is no longer pending");return new Pending(id,UUID.fromString(r.getString("player_uuid")),r.getBytes("inventory"),r.getBytes("cursor"),images,moves,r.getString("cursor_token"),r.getObject("cursor_revision")==null?null:r.getLong("cursor_revision"));}}
    }
    public static Set<String> invalidated(Connection db,Pending pending)throws SQLException {
        var tokens=new HashSet<String>();for(var move:pending.moves()){var current=BonusGoodieStore.origin(db,move.before().origin());if(current==null)tokens.add(move.before().token());else if(!move.before().equals(current))throw new SQLException("Goodie changed while chest transfer was pending");}return tokens;
    }
    public static boolean complete(Connection db,String id)throws SQLException {
        return complete(db,id,null);
    }
    public static boolean complete(Connection db,String id,byte[] plainCursor)throws SQLException {
        transaction(db);boolean active;try(var s=db.prepareStatement("SELECT 1 FROM goodie_chest_transfers WHERE id=? AND status='PREPARED'")){s.setString(1,id);try(var r=s.executeQuery()){active=r.next();}}if(!active)return false;
        var intent=read(db,id);var invalid=invalidated(db,intent);
        com.avrgaming.civcraft.modern.campnpc.quest.QuestSubmissionStore.complete(db,id);
        com.avrgaming.civcraft.modern.campnpc.reward.QuestRewardStore.complete(db,id);
        for(var move:intent.moves())if(!invalid.contains(move.before().token())){var after=BonusGoodieStore.move(db,move.before(),move.kind(),move.holder(),null);if(move.drop()!=null)GoodieDepartureStore.queueGround(db,intent.player(),after,move.drop());}
        try(var s=db.prepareStatement("DELETE FROM goodie_chest_cursor_checkpoints WHERE player_uuid=?")){s.setString(1,intent.player().toString());s.executeUpdate();}
        if(intent.cursorToken()!=null && !invalid.contains(intent.cursorToken()))try(var s=db.prepareStatement("INSERT INTO goodie_chest_cursor_checkpoints VALUES(?,?,?,?,?)")){s.setString(1,intent.player().toString());s.setString(2,id);s.setString(3,intent.cursorToken());s.setLong(4,intent.cursorRevision());s.setBytes(5,intent.cursor());s.executeUpdate();}
        try(var s=db.prepareStatement("DELETE FROM goodie_plain_cursor_deliveries WHERE player_uuid=?")){s.setString(1,intent.player().toString());s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO goodie_plain_cursor_deliveries VALUES(?,?,?,?)")){s.setString(1,intent.player().toString());s.setString(2,id);s.setBytes(3,plainCursor==null?intent.cursor():bounded(plainCursor));s.setString(4,plainCursor==null?"CLEAR":"PREPARED");s.executeUpdate();}
        try(var s=db.prepareStatement("UPDATE goodie_chest_transfers SET status='COMPLETE',inventory=X'',cursor=X'' WHERE id=?")){s.setString(1,id);s.executeUpdate();}
        try(var s=db.prepareStatement("UPDATE goodie_chest_images SET before_data=X'',after_data=X'' WHERE operation_id=?")){s.setString(1,id);s.executeUpdate();}return true;
    }
    public static Cursor cursor(Connection db,UUID player)throws SQLException {
        try(var s=db.prepareStatement("SELECT receipt_id,token,revision,item FROM goodie_chest_cursor_checkpoints WHERE player_uuid=?")){s.setString(1,player.toString());try(var r=s.executeQuery()){return r.next()?new Cursor(r.getString(1),r.getString(2),r.getLong(3),r.getBytes(4)):null;}}
    }
    public static PlainCursor plainCursor(Connection db,UUID player)throws SQLException {
        try(var s=db.prepareStatement("SELECT receipt_id,item,status FROM goodie_plain_cursor_deliveries WHERE player_uuid=? AND status IN ('PREPARED','CLEAR')")){s.setString(1,player.toString());try(var r=s.executeQuery()){return r.next()?new PlainCursor(r.getString(1),r.getBytes(2),r.getString(3).equals("CLEAR")):null;}}
    }
    public static void deliveredPlainCursor(Connection db,UUID player,String receipt)throws SQLException {
        transaction(db);try(var s=db.prepareStatement("UPDATE goodie_plain_cursor_deliveries SET status='DELIVERED',item=X'' WHERE player_uuid=? AND receipt_id=? AND status IN ('PREPARED','CLEAR')")){s.setString(1,player.toString());s.setString(2,receipt);s.executeUpdate();}
    }
    public static Map<String,Position> knownContainers(Connection db)throws SQLException {
        var out=new HashMap<String,Position>();try(var s=db.createStatement();var r=s.executeQuery("SELECT DISTINCT world,x,y,z FROM goodie_chest_images")){while(r.next()){var p=new Position(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4));out.put(p.holder(),p);}}return out;
    }
    public static Set<String> storedContainers(Connection db)throws SQLException {
        var out=new LinkedHashSet<String>();try(var s=db.createStatement();var r=s.executeQuery("SELECT DISTINCT holder_id FROM bonus_goodies WHERE holder_kind='CONTAINER' ORDER BY holder_id")){while(r.next())out.add(r.getString(1));}return out;
    }
    public static void returnMissing(Connection db,Position position,Set<String> present)throws SQLException {
        transaction(db);requirePositionsAvailable(db,List.of(position));
        var origins=new ArrayList<Long>();try(var s=db.prepareStatement("SELECT origin_build FROM bonus_goodies WHERE holder_kind='CONTAINER' AND holder_id=?")){s.setString(1,position.holder());try(var r=s.executeQuery()){while(r.next())origins.add(r.getLong(1));}}
        for(long origin:origins){var current=BonusGoodieStore.origin(db,origin);if(current!=null && !present.contains(current.token())){GoodieTransferStore.requireOriginUnlocked(db,origin);BonusGoodieStore.returnHome(db,current);}}
    }
}
