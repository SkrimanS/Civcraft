package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import java.util.*;

/** Birth reservations precede entity persistence; unloaded animals retain their pasture membership. */
public final class PastureStore {
    private PastureStore() {}
    public record Animal(UUID uuid,long pasture,String world,int chunkX,int chunkZ,String type,String state) {}
    private static void transaction(Connection db)throws SQLException{if(db.getAutoCommit())throw new SQLException("Pasture operation requires a transaction");}
    public static void install(Connection db)throws SQLException{
        try(var s=db.createStatement()){
            s.executeUpdate("CREATE TABLE IF NOT EXISTS pasture_animals(uuid TEXT PRIMARY KEY,build_id INTEGER NOT NULL REFERENCES structure_builds(id) ON DELETE CASCADE,world TEXT NOT NULL,chunk_x INTEGER NOT NULL,chunk_z INTEGER NOT NULL,type TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('PENDING','ACTIVE')))");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS pasture_animals_build ON pasture_animals(build_id)");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS pasture_birth_blocks_delete BEFORE DELETE ON structure_builds WHEN EXISTS(SELECT 1 FROM pasture_animals WHERE build_id=OLD.id AND state='PENDING') BEGIN SELECT RAISE(ABORT,'Pasture birth is pending'); END");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS pasture_birth_blocks_build_change BEFORE UPDATE OF town_id,status ON structure_builds WHEN (NEW.town_id IS NOT OLD.town_id OR NEW.status IS NOT OLD.status) AND EXISTS(SELECT 1 FROM pasture_animals WHERE build_id=OLD.id AND state='PENDING') BEGIN SELECT RAISE(ABORT,'Pasture birth is pending'); END");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS pasture_birth_blocks_civ_change BEFORE UPDATE OF civ_id ON towns WHEN NEW.civ_id IS NOT OLD.civ_id AND EXISTS(SELECT 1 FROM pasture_animals a JOIN structure_builds b ON b.id=a.build_id WHERE b.town_id=OLD.id AND a.state='PENDING') BEGIN SELECT RAISE(ABORT,'Pasture birth is pending'); END");
        }
    }
    public static OptionalLong at(Connection db,String world,int x,int z)throws SQLException{
        try(var s=db.prepareStatement("SELECT b.id FROM structure_chunks c JOIN active_structure_builds b ON b.id=c.build_id WHERE c.world=? AND c.chunk_x=? AND c.chunk_z=? AND b.structure_id='ti_pasture' AND b.status='COMPLETE' ORDER BY b.id DESC LIMIT 1")){s.setString(1,world);s.setInt(2,x);s.setInt(3,z);try(var r=s.executeQuery()){return r.next()?OptionalLong.of(r.getLong(1)):OptionalLong.empty();}}
    }
    public static boolean exists(Connection db,long pasture)throws SQLException{
        try(var s=db.prepareStatement("SELECT 1 FROM structure_builds WHERE id=? AND structure_id='ti_pasture' AND status<>'DEMOLISHED'")){s.setLong(1,pasture);try(var r=s.executeQuery()){return r.next();}}
    }
    public static boolean active(Connection db,long pasture)throws SQLException{
        try(var s=db.prepareStatement("SELECT 1 FROM active_structure_builds b LEFT JOIN building_combat_health h ON h.build_id=b.id WHERE b.id=? AND b.structure_id='ti_pasture' AND b.status='COMPLETE' AND COALESCE(h.hitpoints,b.max_hitpoints)>0")){s.setLong(1,pasture);try(var r=s.executeQuery()){return r.next();}}
    }
    public static long count(Connection db,long pasture)throws SQLException{
        try(var s=db.prepareStatement("SELECT count(*) FROM pasture_animals WHERE build_id=?")){s.setLong(1,pasture);try(var r=s.executeQuery()){r.next();return r.getLong(1);}}
    }
    public static Animal find(Connection db,UUID uuid)throws SQLException{
        try(var s=db.prepareStatement("SELECT * FROM pasture_animals WHERE uuid=?")){s.setString(1,uuid.toString());try(var r=s.executeQuery()){return r.next()?read(r):null;}}
    }
    private static Animal read(ResultSet r)throws SQLException{return new Animal(UUID.fromString(r.getString("uuid")),r.getLong("build_id"),r.getString("world"),r.getInt("chunk_x"),r.getInt("chunk_z"),r.getString("type"),r.getString("state"));}
    public static List<Animal> animals(Connection db,boolean pendingOnly)throws SQLException{
        var result=new ArrayList<Animal>();try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM pasture_animals"+(pendingOnly?" WHERE state='PENDING'":"")+" ORDER BY build_id,uuid")){while(r.next())result.add(read(r));}return List.copyOf(result);
    }
    public static Animal reserve(Connection db,UUID uuid,long pasture,String world,int x,int z,String type,int maximum)throws SQLException{
        transaction(db);if(!active(db,pasture))throw new IllegalArgumentException("Пастбище не работает или разрушено.");
        var existing=find(db,uuid);if(existing!=null){if(existing.pasture()!=pasture || !existing.type().equals(type))throw new SQLException("Animal already belongs to another pasture");return existing;}
        if(!PastureRules.room(count(db,pasture),0,maximum))throw new IllegalArgumentException("Пастбище заполнено. Освободите место для потомства.");
        try(var s=db.prepareStatement("INSERT INTO pasture_animals VALUES(?,?,?,?,?,?,'PENDING')")){s.setString(1,uuid.toString());s.setLong(2,pasture);s.setString(3,world);s.setInt(4,x);s.setInt(5,z);s.setString(6,type);s.executeUpdate();}return find(db,uuid);
    }
    public static boolean confirm(Connection db,UUID uuid)throws SQLException{
        transaction(db);try(var s=db.prepareStatement("UPDATE pasture_animals SET state='ACTIVE' WHERE uuid=? AND state='PENDING'")){s.setString(1,uuid.toString());return s.executeUpdate()==1;}
    }
    public static void cancel(Connection db,UUID uuid)throws SQLException{
        transaction(db);try(var s=db.prepareStatement("DELETE FROM pasture_animals WHERE uuid=? AND state='PENDING'")){s.setString(1,uuid.toString());s.executeUpdate();}
    }
    public static void remove(Connection db,UUID uuid)throws SQLException{
        transaction(db);try(var s=db.prepareStatement("DELETE FROM pasture_animals WHERE uuid=?")){s.setString(1,uuid.toString());s.executeUpdate();}
    }
    public static void locate(Connection db,UUID uuid,String world,int x,int z)throws SQLException{
        transaction(db);try(var s=db.prepareStatement("UPDATE pasture_animals SET world=?,chunk_x=?,chunk_z=? WHERE uuid=?")){s.setString(1,world);s.setInt(2,x);s.setInt(3,z);s.setString(4,uuid.toString());s.executeUpdate();}
    }
    public static void restore(Connection db,UUID uuid,long pasture,String world,int x,int z,String type)throws SQLException{
        transaction(db);if(!exists(db,pasture))return;
        var existing=find(db,uuid);if(existing!=null){if(existing.pasture()!=pasture || !existing.type().equals(type))throw new SQLException("Pasture identity changed");locate(db,uuid,world,x,z);return;}
        try(var s=db.prepareStatement("INSERT INTO pasture_animals VALUES(?,?,?,?,?,?,'ACTIVE')")){s.setString(1,uuid.toString());s.setLong(2,pasture);s.setString(3,world);s.setInt(4,x);s.setInt(5,z);s.setString(6,type);s.executeUpdate();}
    }
    public static void releaseDemolished(Connection db)throws SQLException{
        transaction(db);try(var s=db.createStatement()){s.executeUpdate("DELETE FROM pasture_animals WHERE build_id IN (SELECT id FROM structure_builds WHERE status='DEMOLISHED' OR structure_id<>'ti_pasture')");}
    }
}
