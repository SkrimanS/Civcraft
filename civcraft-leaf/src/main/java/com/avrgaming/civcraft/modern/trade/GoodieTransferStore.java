package com.avrgaming.civcraft.modern.trade;

import java.sql.*;
import java.util.*;

/** Commit only after the player image and affected frames have been flushed. */
public final class GoodieTransferStore {
    private GoodieTransferStore() {}
    public record Pending(String id,UUID player,BonusGoodieStore.State before,BonusGoodieStore.State after,
                          String fromFrame,String toFrame,byte[] inventory,Drop drop) {
        public Pending {inventory=inventory.clone();}
        @Override public byte[] inventory(){return inventory.clone();}
    }
    public record Drop(String world,double x,double y,double z) {
        public Drop {
            if(world==null||world.isBlank()||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))
                throw new IllegalArgumentException("Неверное место выпадения торгового товара");
        }
    }
    public enum Completion { COMPLETE, INVALIDATED, ALREADY_COMPLETE }
    public static void install(Connection db)throws SQLException {
        GoodieFrameStore.install(db);
        GoodieChestStore.install(db);
        try(var s=db.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS goodie_transfers(
                id TEXT PRIMARY KEY,player_uuid TEXT NOT NULL,origin_build INTEGER NOT NULL,good_id TEXT NOT NULL,
                token TEXT NOT NULL,before_revision INTEGER NOT NULL,before_kind TEXT NOT NULL,before_holder TEXT NOT NULL,before_town INTEGER,
                after_kind TEXT NOT NULL,after_holder TEXT NOT NULL,after_town INTEGER,from_frame TEXT,to_frame TEXT,
                inventory BLOB NOT NULL,status TEXT NOT NULL DEFAULT 'PREPARED' CHECK(status IN ('PREPARED','COMPLETE','INVALIDATED')),created_at INTEGER NOT NULL)
                """);
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_pending_goodie_transfer ON goodie_transfers(origin_build) WHERE status='PREPARED'");
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_pending_goodie_player ON goodie_transfers(player_uuid) WHERE status='PREPARED'");
            var columns=new HashSet<String>();try(var r=s.executeQuery("PRAGMA table_info(goodie_transfers)")){while(r.next())columns.add(r.getString("name"));}
            for(String column:List.of("drop_world","drop_x","drop_y","drop_z"))if(!columns.contains(column))
                s.executeUpdate("ALTER TABLE goodie_transfers ADD COLUMN "+column+(column.equals("drop_world")?" TEXT":" REAL"));
        }
    }
    private static void transaction(Connection db)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Goodie transfer requires a transaction");
    }
    public static Set<String> lockedFrames(Connection db)throws SQLException {
        var result=new HashSet<String>();
        try(var s=db.createStatement();var r=s.executeQuery("SELECT from_frame,to_frame FROM goodie_transfers WHERE status='PREPARED'")) {
            while(r.next()){if(r.getString(1)!=null)result.add(r.getString(1));if(r.getString(2)!=null)result.add(r.getString(2));}
        }return Set.copyOf(result);
    }
    public static void requireOriginUnlocked(Connection db,long build)throws SQLException {
        GoodieChestStore.requireOriginUnlocked(db,build);
        try(var s=db.prepareStatement("SELECT 1 FROM goodie_transfers WHERE origin_build=? AND status='PREPARED'")) {
            s.setLong(1,build);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Перенос торгового товара ещё сохраняется.");}
        }
    }
    private static String frame(BonusGoodieStore.State state) {
        return switch(state.kind()){case SOURCE->state.origin()+":-1";case FRAME->state.holder();default->null;};
    }
    public static Pending prepare(Connection db,String id,UUID player,BonusGoodieStore.State before,
                                  BonusGoodieStore.Kind kind,String holder,Long town,byte[] inventory,long now)throws SQLException {
        return prepare(db,id,player,before,kind,holder,town,inventory,null,now);
    }
    public static Pending prepare(Connection db,String id,UUID player,BonusGoodieStore.State before,
                                  BonusGoodieStore.Kind kind,String holder,Long town,byte[] inventory,Drop drop,long now)throws SQLException {
        transaction(db);
        if(id==null||id.isBlank()||player==null||inventory==null||inventory.length==0||inventory.length>16*1024*1024||now<0)
            throw new IllegalArgumentException("Неверный план переноса торгового товара");
        if(!before.equals(BonusGoodieStore.origin(db,before.origin())))throw new IllegalArgumentException("Состояние товара уже изменилось.");
        GoodieChestStore.requireOriginUnlocked(db,before.origin());
        boolean taking=kind==BonusGoodieStore.Kind.PLAYER&&(before.kind()==BonusGoodieStore.Kind.SOURCE||before.kind()==BonusGoodieStore.Kind.FRAME||before.kind()==BonusGoodieStore.Kind.ITEM);
        boolean placing=before.kind()==BonusGoodieStore.Kind.PLAYER&&(kind==BonusGoodieStore.Kind.SOURCE||kind==BonusGoodieStore.Kind.FRAME);
        boolean dropping=kind==BonusGoodieStore.Kind.ITEM&&(before.kind()==BonusGoodieStore.Kind.SOURCE||before.kind()==BonusGoodieStore.Kind.FRAME||before.kind()==BonusGoodieStore.Kind.PLAYER);
        if(!taking&&!placing&&!dropping)throw new IllegalArgumentException("Недопустимый перенос торгового товара.");
        if((before.kind()==BonusGoodieStore.Kind.PLAYER&&!before.holder().equals(player.toString()))||(taking&&!player.toString().equals(holder)))throw new IllegalArgumentException("Товар переносит другой игрок.");
        if((dropping&&(drop==null||!id.equals(holder)))||(!dropping&&drop!=null))throw new IllegalArgumentException("Выпадение товара требует сохранённой позиции и идентификатора операции.");
        if(holder==null||holder.isBlank()||(town!=null&&kind!=BonusGoodieStore.Kind.FRAME))throw new IllegalArgumentException("Неверное место назначения торгового товара");
        if(kind==BonusGoodieStore.Kind.SOURCE&&!holder.equals(Long.toString(before.origin())))throw new IllegalArgumentException("Товар возвращается только в свой исходный пост.");
        var after=new BonusGoodieStore.State(before.origin(),before.good(),before.token(),Math.addExact(before.revision(),1),kind,holder,town);
        String from=frame(before),to=frame(after);var locked=lockedFrames(db);
        if((from!=null&&locked.contains(from))||(to!=null&&locked.contains(to)))throw new IllegalArgumentException("Рамка участвует в незавершённом переносе.");
        if(from!=null&&GoodieFrameStore.find(db,from)==null)throw new IllegalArgumentException("Исходная рамка не найдена.");
        if(to!=null)validateTarget(db,after,to);
        try(var s=db.prepareStatement("INSERT INTO goodie_transfers(id,player_uuid,origin_build,good_id,token,before_revision,before_kind,before_holder,before_town,after_kind,after_holder,after_town,from_frame,to_frame,inventory,drop_world,drop_x,drop_y,drop_z,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PREPARED',?)")) {
            int i=1;s.setString(i++,id);s.setString(i++,player.toString());s.setLong(i++,before.origin());s.setString(i++,before.good());s.setString(i++,before.token());
            s.setLong(i++,before.revision());s.setString(i++,before.kind().name());s.setString(i++,before.holder());nullable(s,i++,before.hostTown());
            s.setString(i++,kind.name());s.setString(i++,holder);nullable(s,i++,town);s.setString(i++,from);s.setString(i++,to);s.setBytes(i++,inventory);
            s.setString(i++,drop==null?null:drop.world());
            for(double value:drop==null?new double[]{0,0,0}:new double[]{drop.x(),drop.y(),drop.z()}){if(drop==null)s.setNull(i++,Types.DOUBLE);else s.setDouble(i++,value);}
            s.setLong(i,now);s.executeUpdate();
        }
        return new Pending(id,player,before,after,from,to,inventory,drop);
    }
    private static void validateTarget(Connection db,BonusGoodieStore.State after,String key)throws SQLException {
        var target=GoodieFrameStore.find(db,key);if(target==null)throw new IllegalArgumentException("Рамка назначения не найдена.");
        if(target.source()) {
            if(after.hostTown()!=null)throw new IllegalArgumentException("Бонусы города действуют только в рамках ратуши.");
            var original=BonusGoodieStore.origin(db,target.build());
            if(original!=null&&original.kind()==BonusGoodieStore.Kind.SOURCE)throw new IllegalArgumentException("В исходной рамке уже находится товар.");
        }
        try(var s=db.prepareStatement("SELECT town_id FROM structure_builds WHERE id=? AND status='COMPLETE'")) {
            s.setLong(1,target.build());try(var r=s.executeQuery()) {
                if(!r.next())throw new IllegalArgumentException("Постройка рамки не действует.");
                if(!target.source()&&!Objects.equals(after.hostTown(),r.getObject(1)==null?null:r.getLong(1)))throw new IllegalArgumentException("Неверный город рамки.");
            }
        }
        try(var s=db.prepareStatement("SELECT 1 FROM bonus_goodies WHERE holder_kind='FRAME' AND holder_id=?")) {
            s.setString(1,key);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("В рамке уже находится товар.");}
        }
    }
    private static void nullable(PreparedStatement s,int index,Long value)throws SQLException {if(value==null)s.setNull(index,Types.BIGINT);else s.setLong(index,value);}
    public static List<Pending> pending(Connection db)throws SQLException {
        var result=new ArrayList<Pending>();try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM goodie_transfers WHERE status='PREPARED' ORDER BY created_at,id")){while(r.next())result.add(read(r));}return List.copyOf(result);
    }
    public static Completion complete(Connection db,String id)throws SQLException {
        transaction(db);Pending operation;
        try(var s=db.prepareStatement("SELECT * FROM goodie_transfers WHERE id=?")) {
            s.setString(1,id);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Unknown goodie transfer");if(!r.getString("status").equals("PREPARED"))return Completion.ALREADY_COMPLETE;operation=read(r);}
        }
        var current=BonusGoodieStore.origin(db,operation.before().origin());String status;
        if(current==null)status="INVALIDATED";
        else {
            if(!current.equals(operation.before()))throw new SQLException("Goodie changed during transfer recovery");
            var after=operation.after();BonusGoodieStore.move(db,current,after.kind(),after.holder(),after.hostTown());status="COMPLETE";
        }
        try(var s=db.prepareStatement("UPDATE goodie_transfers SET status=?,inventory=X'' WHERE id=? AND status='PREPARED'")) {
            s.setString(1,status);s.setString(2,id);if(s.executeUpdate()!=1)throw new SQLException("Goodie transfer changed before completion");
        }
        return status.equals("COMPLETE")?Completion.COMPLETE:Completion.INVALIDATED;
    }
    private static Pending read(ResultSet r)throws SQLException {
        long origin=r.getLong("origin_build"),revision=r.getLong("before_revision");String good=r.getString("good_id"),token=r.getString("token");
        var before=new BonusGoodieStore.State(origin,good,token,revision,BonusGoodieStore.Kind.valueOf(r.getString("before_kind")),r.getString("before_holder"),r.getObject("before_town")==null?null:r.getLong("before_town"));
        var after=new BonusGoodieStore.State(origin,good,token,Math.addExact(revision,1),BonusGoodieStore.Kind.valueOf(r.getString("after_kind")),r.getString("after_holder"),r.getObject("after_town")==null?null:r.getLong("after_town"));
        Drop drop=r.getString("drop_world")==null?null:new Drop(r.getString("drop_world"),r.getDouble("drop_x"),r.getDouble("drop_y"),r.getDouble("drop_z"));
        return new Pending(r.getString("id"),UUID.fromString(r.getString("player_uuid")),before,after,r.getString("from_frame"),r.getString("to_frame"),r.getBytes("inventory"),drop);
    }
}
