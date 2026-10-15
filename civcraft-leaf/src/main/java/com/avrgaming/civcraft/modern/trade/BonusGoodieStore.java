package com.avrgaming.civcraft.modern.trade;

import java.sql.*;
import java.util.*;

/** One live identity per outpost, independent of which civilization currently holds the item. */
public final class BonusGoodieStore {
    private BonusGoodieStore() {}
    public enum Kind { SOURCE, PLAYER, FRAME, ITEM, CONTAINER }
    public record State(long origin,String good,String token,long revision,Kind kind,String holder,Long hostTown) {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS bonus_goodies(
                origin_build INTEGER PRIMARY KEY REFERENCES structure_builds(id) ON DELETE CASCADE,
                good_id TEXT NOT NULL,token TEXT NOT NULL UNIQUE,revision INTEGER NOT NULL DEFAULT 0,
                holder_kind TEXT NOT NULL CHECK(holder_kind IN ('SOURCE','PLAYER','FRAME','ITEM','CONTAINER')),
                holder_id TEXT NOT NULL,host_town INTEGER REFERENCES towns(id) ON DELETE SET NULL,
                CHECK(revision>=0),CHECK(host_town IS NULL OR holder_kind='FRAME'))
                """);
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_goodie_per_frame ON bonus_goodies(holder_id) WHERE holder_kind='FRAME'");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS invalidate_destroyed_goodie AFTER UPDATE OF status ON structure_builds WHEN NEW.status IN ('WAR_DESTROY_PENDING','WAR_DESTROYED','DEMOLISHED','CANCELLED') BEGIN DELETE FROM bonus_goodies WHERE origin_build=NEW.id; END");
        }
    }
    private static void transaction(Connection db)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Goodie transition requires a transaction");
    }
    public static State origin(Connection db,long build)throws SQLException {
        try(var s=db.prepareStatement("SELECT * FROM bonus_goodies WHERE origin_build=?")){s.setLong(1,build);try(var r=s.executeQuery()){return r.next()?read(r):null;}}
    }
    public static State token(Connection db,String token)throws SQLException {
        if(token==null)return null;
        try(var s=db.prepareStatement("SELECT * FROM bonus_goodies WHERE token=?")){s.setString(1,token);try(var r=s.executeQuery()){return r.next()?read(r):null;}}
    }
    public static State ensure(Connection db,TradeGoodsCatalog catalog,long build)throws SQLException {
        transaction(db);String good,type;
        try(var s=db.prepareStatement("SELECT d.good_id,b.structure_id FROM trade_deposits d JOIN structure_builds b ON b.id=d.build_id LEFT JOIN building_combat_health h ON h.build_id=b.id WHERE b.id=? AND b.status='COMPLETE' AND COALESCE(h.hitpoints,b.max_hitpoints)>0")) {
            s.setLong(1,build);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Товар создаётся только в действующем торговом посту или лодке.");good=r.getString(1);type=r.getString(2);}
        }
        if(!TradeDepositStore.outpost(type)||catalog.require(good).water()!=type.equals("ti_fishing_boat"))throw new IllegalArgumentException("Товар не соответствует торговой постройке.");
        State existing=origin(db,build);
        if(existing!=null){if(!existing.good().equals(good))throw new SQLException("Outpost trade good changed");return existing;}
        try(var s=db.prepareStatement("INSERT INTO bonus_goodies VALUES(?,?,?,0,'SOURCE',?,NULL)")) {
            s.setLong(1,build);s.setString(2,good);s.setString(3,UUID.randomUUID().toString());s.setString(4,Long.toString(build));s.executeUpdate();
        }
        return origin(db,build);
    }
    public static State move(Connection db,State before,Kind kind,String holder,Long hostTown)throws SQLException {
        transaction(db);Objects.requireNonNull(kind);
        if(holder==null||holder.isBlank()||(hostTown!=null&&kind!=Kind.FRAME))throw new IllegalArgumentException("Invalid goodie holder");
        if(kind==Kind.SOURCE&&!holder.equals(Long.toString(before.origin())))throw new IllegalArgumentException("Goodie must return to its own outpost");
        try(var s=db.prepareStatement("UPDATE bonus_goodies SET holder_kind=?,holder_id=?,host_town=?,revision=revision+1 WHERE origin_build=? AND token=? AND revision=?")) {
            s.setString(1,kind.name());s.setString(2,holder);if(hostTown==null)s.setNull(3,Types.BIGINT);else s.setLong(3,hostTown);
            s.setLong(4,before.origin());s.setString(5,before.token());s.setLong(6,before.revision());
            if(s.executeUpdate()!=1)throw new IllegalArgumentException("Торговый товар уже перемещён или больше не действует.");
        }
        return origin(db,before.origin());
    }
    public static State returnHome(Connection db,State before)throws SQLException {
        transaction(db);
        try(var s=db.prepareStatement("UPDATE bonus_goodies SET token=?,revision=revision+1,holder_kind='SOURCE',holder_id=?,host_town=NULL WHERE origin_build=? AND token=? AND revision=?")) {
            s.setString(1,UUID.randomUUID().toString());s.setString(2,Long.toString(before.origin()));s.setLong(3,before.origin());s.setString(4,before.token());s.setLong(5,before.revision());
            if(s.executeUpdate()!=1)throw new IllegalArgumentException("Торговый товар уже возвращён или больше не действует.");
        }
        return origin(db,before.origin());
    }
    public static List<State> framed(Connection db,long town)throws SQLException {
        var result=new ArrayList<State>();
        try(var s=db.prepareStatement("SELECT * FROM bonus_goodies WHERE holder_kind='FRAME' AND host_town=? ORDER BY origin_build")) {
            s.setLong(1,town);try(var r=s.executeQuery()){while(r.next())result.add(read(r));}
        }return List.copyOf(result);
    }
    public static State frame(Connection db,String key)throws SQLException {
        try(var s=db.prepareStatement("SELECT * FROM bonus_goodies WHERE holder_kind='FRAME' AND holder_id=?")){s.setString(1,key);try(var r=s.executeQuery()){return r.next()?read(r):null;}}
    }
    private static State read(ResultSet r)throws SQLException {
        return new State(r.getLong("origin_build"),r.getString("good_id"),r.getString("token"),r.getLong("revision"),Kind.valueOf(r.getString("holder_kind")),r.getString("holder_id"),r.getObject("host_town")==null?null:r.getLong("host_town"));
    }
}
