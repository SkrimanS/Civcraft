package com.avrgaming.civcraft.modern.foundation;

import java.sql.*;
import java.util.*;

/** Records produced by creation are bound before their own transaction commits. */
public final class FoundationCreationStore {
    private FoundationCreationStore() {}
    public record Draft(boolean nativeSettler,String name,String townName,String tag,String structure,String direction,String world,int x,int y,int z,int centerX,int centerY,int centerZ,double fee) {}
    public record Plan(String id,UUID player,String kind,String state,boolean nativeSettler,String name,String townName,String tag,String structure,String direction,String world,int x,int y,int z,int centerX,int centerY,int centerZ,double fee,Long beforeCamp,Long beforeCiv,Long beforeTown,Long camp,Long civ,Long town,Long build,boolean ready) {}
    private static void transaction(Connection db)throws SQLException{if(db.getAutoCommit())throw new SQLException("Foundation creation requires a transaction");}
    public static boolean installed(Connection db)throws SQLException{try(var s=db.createStatement();var r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='foundation_creation_plans'")){return r.next();}}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS foundation_creation_plans(
                  escrow_id TEXT PRIMARY KEY REFERENCES foundation_escrows(id),native_settler INTEGER NOT NULL,
                  settlement_name TEXT NOT NULL,town_name TEXT NOT NULL,tag TEXT NOT NULL,structure_id TEXT NOT NULL,direction TEXT NOT NULL,
                  world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,
                  center_x INTEGER NOT NULL,center_y INTEGER NOT NULL,center_z INTEGER NOT NULL,fee REAL NOT NULL,
                  before_camp INTEGER,before_civ INTEGER,before_town INTEGER,
                  created_camp INTEGER,created_civ INTEGER,created_town INTEGER,created_build INTEGER,
                  build_ready INTEGER NOT NULL DEFAULT 0)
                """);
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS foundation_creation_build ON foundation_creation_plans(created_build) WHERE created_build IS NOT NULL");
        }
        FoundationCreationRollback.install(db);
    }
    private static Long number(ResultSet r,String column)throws SQLException{return r.getObject(column)==null?null:r.getLong(column);}
    public static Plan find(Connection db,String id)throws SQLException {
        if(!installed(db))return null;
        try(var s=db.prepareStatement("SELECT p.*,e.player_uuid,e.kind,e.state FROM foundation_creation_plans p JOIN foundation_escrows e ON e.id=p.escrow_id WHERE p.escrow_id=?")) {
            s.setString(1,id);try(var r=s.executeQuery()){if(!r.next())return null;
                return new Plan(id,UUID.fromString(r.getString("player_uuid")),r.getString("kind"),r.getString("state"),r.getBoolean("native_settler"),r.getString("settlement_name"),r.getString("town_name"),r.getString("tag"),r.getString("structure_id"),r.getString("direction"),r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getInt("center_x"),r.getInt("center_y"),r.getInt("center_z"),r.getDouble("fee"),number(r,"before_camp"),number(r,"before_civ"),number(r,"before_town"),number(r,"created_camp"),number(r,"created_civ"),number(r,"created_town"),number(r,"created_build"),r.getBoolean("build_ready"));
            }
        }
    }
    public static void prepare(Connection db,String id,Draft draft)throws SQLException {
        transaction(db);var escrow=FoundationEscrowStore.find(db,id);
        if(escrow==null || !escrow.state().equals("RESERVED"))throw new IllegalArgumentException("Предмет основания не готов к созданию поселения.");
        if(draft.name()==null || draft.name().isBlank() || draft.structure()==null || draft.structure().isBlank() || draft.world()==null || draft.world().isBlank() || !Double.isFinite(draft.fee()) || draft.fee()<0)throw new IllegalArgumentException("Неверный план основания поселения.");
        Long camp,civ,town;
        try(var s=db.prepareStatement("SELECT camp_id,civ_id,town_id FROM residents WHERE uuid=?")){s.setString(1,escrow.player().toString());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Основатель больше не зарегистрирован.");camp=number(r,"camp_id");civ=number(r,"civ_id");town=number(r,"town_id");}}
        if(camp!=null || (!escrow.kind().equals("TOWN") && (civ!=null || town!=null)) || (escrow.kind().equals("TOWN") && (civ==null || (draft.nativeSettler()?town==null:town!=null))))throw new IllegalArgumentException("Членство основателя изменилось до сохранения плана.");
        try(var s=db.prepareStatement("INSERT INTO foundation_creation_plans(escrow_id,native_settler,settlement_name,town_name,tag,structure_id,direction,world,x,y,z,center_x,center_y,center_z,fee,before_camp,before_civ,before_town) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setString(1,id);s.setBoolean(2,draft.nativeSettler());s.setString(3,draft.name());s.setString(4,Objects.toString(draft.townName(),""));s.setString(5,Objects.toString(draft.tag(),""));s.setString(6,draft.structure());s.setString(7,draft.direction());s.setString(8,draft.world());s.setInt(9,draft.x());s.setInt(10,draft.y());s.setInt(11,draft.z());s.setInt(12,draft.centerX());s.setInt(13,draft.centerY());s.setInt(14,draft.centerZ());s.setDouble(15,draft.fee());s.setObject(16,camp);s.setObject(17,civ);s.setObject(18,town);s.executeUpdate();
        }
        FoundationCreationRollback.prepare(db,id,escrow.player());
    }
    private static List<Plan> creating(Connection db,UUID actor)throws SQLException {
        if(!installed(db))return List.of();
        var ids=new ArrayList<String>();try(var s=db.prepareStatement("SELECT p.escrow_id FROM foundation_creation_plans p JOIN foundation_escrows e ON e.id=p.escrow_id WHERE e.player_uuid=? AND e.state='CREATING'")){s.setString(1,actor.toString());try(var r=s.executeQuery()){while(r.next())ids.add(r.getString(1));}}
        var out=new ArrayList<Plan>();for(var id:ids)out.add(find(db,id));return out;
    }
    private static boolean origin(Plan p,String world,int x,int y,int z){return p.world().equals(world) && p.x()==x && p.y()==y && p.z()==z;}
    private static void bind(Connection db,Plan p,String column,Long previous,long value)throws SQLException {
        transaction(db);if(previous!=null && previous!=value)throw new SQLException("Foundation creation already has a different "+column);
        try(var s=db.prepareStatement("UPDATE foundation_creation_plans SET "+column+"=? WHERE escrow_id=?")){s.setLong(1,value);s.setString(2,p.id());s.executeUpdate();}
    }
    public static void captureCamp(Connection db,long id,UUID actor,String name,String world,int x,int y,int z)throws SQLException {
        for(var p:creating(db,actor)){if(!p.kind().equals("CAMP") || !p.name().equals(name) || !origin(p,world,x,y,z))throw new SQLException("Camp does not match pending foundation plan");bind(db,p,"created_camp",p.camp(),id);FoundationCreationRollback.capture(db,p.id(),"camps",id);FoundationCreationRollback.capture(db,p.id(),"camp_members",id);}
    }
    public static void captureCivilization(Connection db,long id,UUID actor,String name,String tag)throws SQLException {
        for(var p:creating(db,actor)){if(!p.kind().equals("CIVILIZATION") || !p.name().equals(name) || !p.tag().equalsIgnoreCase(Objects.toString(tag,"")))throw new SQLException("Civilization does not match pending foundation plan");bind(db,p,"created_civ",p.civ(),id);FoundationCreationRollback.capture(db,p.id(),"civilizations",id);}
    }
    public static void captureTown(Connection db,long id,UUID actor,long civ,String name,String world,int x,int y,int z)throws SQLException {
        for(var p:creating(db,actor)){if(p.kind().equals("CAMP") || !p.townName().equals(name) || !Objects.equals(p.kind().equals("CIVILIZATION")?p.civ():p.beforeCiv(),civ) || !origin(p,world,x,y,z))throw new SQLException("Town does not match pending foundation plan");bind(db,p,"created_town",p.town(),id);FoundationCreationRollback.capture(db,p.id(),"towns",id);}
    }
    public static void captureBuild(Connection db,long id,UUID actor,Long town,String structure,String direction,String world,int x,int y,int z)throws SQLException {
        for(var p:creating(db,actor)){if(!p.structure().equals(structure) || !p.direction().equals(direction) || !origin(p,world,x,y,z) || !(p.kind().equals("CAMP")?town==null && p.camp()!=null:town!=null && town.equals(p.town())))throw new SQLException("Structure does not match pending foundation plan");bind(db,p,"created_build",p.build(),id);}
    }
    public static void buildReady(Connection db,long build)throws SQLException {
        if(!installed(db))return;
        transaction(db);try(var s=db.prepareStatement("UPDATE foundation_creation_plans SET build_ready=1 WHERE created_build=? AND EXISTS(SELECT 1 FROM structure_chunks WHERE build_id=?)")){s.setLong(1,build);s.setLong(2,build);s.executeUpdate();}
    }
    public static boolean held(Connection db,long build)throws SQLException {
        if(!installed(db))return false;
        try(var s=db.prepareStatement("SELECT 1 FROM foundation_creation_plans p JOIN foundation_escrows e ON e.id=p.escrow_id WHERE p.created_build=? AND (e.state<>'COMMITTED' OR p.build_ready=0)")){s.setLong(1,build);try(var r=s.executeQuery()){return r.next();}}
    }
    private static void requireAsset(Connection db,String table,Long id,String ownerColumn,Plan p,String name,boolean located)throws SQLException {
        if(id==null)throw new SQLException("Foundation "+table+" id is not recorded");
        try(var s=db.prepareStatement("SELECT 1 FROM "+table+" WHERE id=? AND "+ownerColumn+"=? AND name=?"+(located?" AND world=? AND x=? AND y=? AND z=?":""))) {
            s.setLong(1,id);s.setString(2,p.player().toString());s.setString(3,name);if(located){s.setString(4,p.world());s.setInt(5,p.x());s.setInt(6,p.y());s.setInt(7,p.z());}try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Recorded foundation "+table+" changed or is missing");}
        }
    }
    private static void claim(Connection db,Plan p,long town,long civ)throws SQLException {
        try(var s=db.prepareStatement("SELECT town_id,civ_id FROM town_claims WHERE world=? AND chunk_x=? AND chunk_z=?")) {
            s.setString(1,p.world());s.setInt(2,p.centerX()>>4);s.setInt(3,p.centerZ()>>4);try(var r=s.executeQuery()){if(r.next()){if(r.getLong(1)!=town || r.getLong(2)!=civ)throw new SQLException("Foundation origin chunk changed ownership");return;}}
        }
        try(var s=db.prepareStatement("INSERT INTO town_claims(world,chunk_x,chunk_z,town_id,civ_id,claimed_by_uuid,claimed_at) VALUES(?,?,?,?,?,?,?)")){s.setString(1,p.world());s.setInt(2,p.centerX()>>4);s.setInt(3,p.centerZ()>>4);s.setLong(4,town);s.setLong(5,civ);s.setString(6,p.player().toString());s.setLong(7,System.currentTimeMillis());s.executeUpdate();}
    }
    public static void commitReady(Connection db,String id,boolean livePaidResult)throws SQLException {
        transaction(db);var p=find(db,id);if(p==null)throw new SQLException("Missing foundation creation plan");
        if(p.state().equals("COMMITTED"))return;
        if(!p.state().equals("CREATING") || !p.ready() || p.build()==null)throw new SQLException("Foundation structure is not fully registered");
        if(p.fee()>0 && !livePaidResult)throw new SQLException("Paid foundation awaits payment reconciliation");
        try(var s=db.prepareStatement("SELECT 1 FROM structure_builds WHERE id=? AND builder_uuid=? AND town_id IS ? AND structure_id=? AND direction=? AND world=? AND x=? AND y=? AND z=? AND status IN ('QUEUED','BUILDING','COMPLETE')")) {
            s.setLong(1,p.build());s.setString(2,p.player().toString());s.setObject(3,p.kind().equals("CAMP")?null:p.town());s.setString(4,p.structure());s.setString(5,p.direction());s.setString(6,p.world());s.setInt(7,p.x());s.setInt(8,p.y());s.setInt(9,p.z());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Foundation structure record changed or is missing");}
        }
        Long camp,civ,town;try(var s=db.prepareStatement("SELECT camp_id,civ_id,town_id FROM residents WHERE uuid=?")){s.setString(1,p.player().toString());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Foundation resident is missing");camp=number(r,"camp_id");civ=number(r,"civ_id");town=number(r,"town_id");}}
        if(p.kind().equals("CAMP")) {
            requireAsset(db,"camps",p.camp(),"owner_uuid",p,p.name(),true);
            if(!Objects.equals(camp,p.camp()) || civ!=null || town!=null)throw new SQLException("Foundation camp membership changed");
        }else {
            requireAsset(db,"towns",p.town(),"mayor_uuid",p,p.townName(),true);
            long expectedCiv=p.kind().equals("CIVILIZATION")?Objects.requireNonNull(p.civ()):Objects.requireNonNull(p.beforeCiv());
            if(p.kind().equals("CIVILIZATION"))requireAsset(db,"civilizations",p.civ(),"leader_uuid",p,p.name(),false);
            try(var s=db.prepareStatement("SELECT civ_id FROM towns WHERE id=?")){s.setLong(1,p.town());try(var r=s.executeQuery()){if(!r.next() || r.getLong(1)!=expectedCiv)throw new SQLException("Foundation town civilization changed");}}
            if(p.nativeSettler() && Objects.equals(town,p.beforeTown()) && Objects.equals(civ,p.beforeCiv())) {
                SettlerTownStore.complete(db,p.player(),p.town(),expectedCiv,p.beforeTown(),p.world(),p.centerX()>>4,p.centerZ()>>4,System.currentTimeMillis());town=p.town();
            }
            if(camp!=null || !Objects.equals(civ,expectedCiv) || !Objects.equals(town,p.town()))throw new SQLException("Foundation town membership changed");
            claim(db,p,p.town(),expectedCiv);
            if(p.kind().equals("CIVILIZATION"))try(var s=db.prepareStatement("UPDATE civilizations SET capital_town_id=? WHERE id=? AND (capital_town_id IS NULL OR capital_town_id=?)")) {
                s.setLong(1,p.town());s.setLong(2,p.civ());s.setLong(3,p.town());if(s.executeUpdate()!=1)throw new SQLException("Foundation civilization capital changed");
            }
        }
        FoundationEscrowStore.committed(db,id);
    }
    public static void cancelEmpty(Connection db,String id)throws SQLException {
        transaction(db);var p=find(db,id);
        if(p==null || !p.state().equals("CREATING") || p.fee()!=0 || hasCreatedRecords(db,id))throw new SQLException("Foundation is not an empty unpaid creation");
        try(var s=db.prepareStatement("SELECT 1 FROM residents WHERE uuid=? AND camp_id IS ? AND civ_id IS ? AND town_id IS ?")) {
            s.setString(1,p.player().toString());s.setObject(2,p.beforeCamp());s.setObject(3,p.beforeCiv());s.setObject(4,p.beforeTown());
            try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Empty foundation membership changed");}
        }
        FoundationEscrowStore.cancel(db,id,true);
    }
    public static boolean hasCreatedRecords(Connection db,String id)throws SQLException {
        var p=find(db,id);return p!=null && (p.camp()!=null || p.civ()!=null || p.town()!=null || p.build()!=null);
    }
}
