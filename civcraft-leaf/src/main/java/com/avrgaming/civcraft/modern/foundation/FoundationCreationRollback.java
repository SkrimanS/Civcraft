package com.avrgaming.civcraft.modern.foundation;

import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Compensation only for pristine creation records which have never had a build. */
public final class FoundationCreationRollback {
    private FoundationCreationRollback() {}
    private static void transaction(Connection db)throws SQLException {if(db.getAutoCommit())throw new SQLException("Foundation compensation requires a transaction");}
    private static String quote(String name){return "\""+name.replace("\"","\"\"")+"\"";}
    private static boolean table(Connection db,String name)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")){s.setString(1,name);try(var r=s.executeQuery()){return r.next();}}
    }
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS foundation_creation_asset_snapshots(escrow_id TEXT NOT NULL REFERENCES foundation_escrows(id),asset_type TEXT NOT NULL,asset_id INTEGER NOT NULL,digest BLOB NOT NULL,PRIMARY KEY(escrow_id,asset_type))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS foundation_creation_resident_state(escrow_id TEXT PRIMARY KEY REFERENCES foundation_escrows(id),debt INTEGER NOT NULL,eviction_days INTEGER NOT NULL,cooldown_civ INTEGER,cooldown_at INTEGER)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS foundation_creation_rollbacks(escrow_id TEXT PRIMARY KEY REFERENCES foundation_escrows(id),completed_at INTEGER NOT NULL)");
        }
    }
    public static void prepare(Connection db,String id,UUID player)throws SQLException {
        transaction(db);long debt=0,days=0;Long cooldownCiv=null,cooldownAt=null;
        var columns=new HashSet<String>();try(var s=db.createStatement();var r=s.executeQuery("PRAGMA table_info(residents)")){while(r.next())columns.add(r.getString("name"));}
        if(columns.contains("debt") && columns.contains("days_til_evict"))try(var s=db.prepareStatement("SELECT debt,days_til_evict FROM residents WHERE uuid=?")){s.setString(1,player.toString());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Foundation resident missing");debt=r.getLong(1);days=r.getLong(2);}}
        if(table(db,"resident_rejoin_cooldowns"))try(var s=db.prepareStatement("SELECT civ_id,departed_at FROM resident_rejoin_cooldowns WHERE player_uuid=?")){s.setString(1,player.toString());try(var r=s.executeQuery()){if(r.next()){cooldownCiv=r.getLong(1);cooldownAt=r.getLong(2);}}}
        try(var s=db.prepareStatement("INSERT INTO foundation_creation_resident_state VALUES(?,?,?,?,?)")){s.setString(1,id);s.setLong(2,debt);s.setLong(3,days);s.setObject(4,cooldownCiv);s.setObject(5,cooldownAt);s.executeUpdate();}
    }
    private static void field(MessageDigest digest,String value) {
        if(value==null){digest.update((byte)0);return;}digest.update((byte)1);
        byte[] data=value.getBytes(StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(data.length).array());digest.update(data);
    }
    private static byte[] digest(Connection db,String table,long id)throws SQLException {
        final MessageDigest hash;try{hash=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException failure){throw new SQLException(failure);}
        String key=table.equals("camp_members")?"camp_id":"id";
        try(var s=db.prepareStatement("SELECT * FROM "+quote(table)+" WHERE "+quote(key)+"=?"+(table.equals("camp_members")?" ORDER BY uuid":""))) {
            s.setLong(1,id);try(var r=s.executeQuery()){int rows=0;var metadata=r.getMetaData();
                for(int i=1;i<=metadata.getColumnCount();i++)field(hash,metadata.getColumnName(i));
                while(r.next()){rows++;hash.update((byte)2);for(int i=1;i<=metadata.getColumnCount();i++){Object value=r.getObject(i);field(hash,value==null?"NULL":value instanceof byte[]?"BLOB":value instanceof Number?"NUMBER":"TEXT");field(hash,value instanceof byte[] bytes?Base64.getEncoder().encodeToString(bytes):r.getString(i));}}
                if(rows==0)throw new SQLException("Foundation snapshot record missing: "+table+" #"+id);
            }
        }
        return hash.digest();
    }
    public static void capture(Connection db,String escrow,String table,long id)throws SQLException {
        transaction(db);byte[] value=digest(db,table,id);
        try(var s=db.prepareStatement("INSERT INTO foundation_creation_asset_snapshots VALUES(?,?,?,?) ON CONFLICT(escrow_id,asset_type) DO NOTHING")){s.setString(1,escrow);s.setString(2,table);s.setLong(3,id);s.setBytes(4,value);s.executeUpdate();}
        requireSnapshot(db,escrow,table,id);
    }
    private static void requireSnapshot(Connection db,String escrow,String table,long id)throws SQLException {
        byte[] original;
        try(var s=db.prepareStatement("SELECT digest FROM foundation_creation_asset_snapshots WHERE escrow_id=? AND asset_type=? AND asset_id=?")){s.setString(1,escrow);s.setString(2,table);s.setLong(3,id);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Foundation has no creation snapshot: "+table);original=r.getBytes(1);}}
        if(!MessageDigest.isEqual(original,digest(db,table,id)))throw new SQLException("Foundation record changed since creation: "+table+" #"+id);
    }
    public static boolean completed(Connection db,String id)throws SQLException {
        if(!table(db,"foundation_creation_rollbacks"))return false;
        try(var s=db.prepareStatement("SELECT 1 FROM foundation_creation_rollbacks WHERE escrow_id=?")){s.setString(1,id);try(var r=s.executeQuery()){return r.next();}}
    }
    private static boolean namedReference(String column,String asset) {
        return switch(asset) {
            case "camps" -> column.equals("camp") || column.equals("camp_id") || column.endsWith("_camp_id") || column.endsWith("_camp");
            case "towns" -> column.equals("town") || column.equals("town_id") || column.endsWith("_town_id") || column.endsWith("_town");
            case "civilizations" -> column.equals("civ") || column.equals("civilization") || column.equals("civ_id") || column.equals("civilization_id") || column.endsWith("_civ_id") || column.endsWith("_civ");
            default -> false;
        };
    }
    private static void requireNoOtherReferences(Connection db,FoundationCreationStore.Plan p,String asset,Long id)throws SQLException {
        if(id==null)return;
        var tables=new ArrayList<String>();try(var s=db.createStatement();var r=s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'foundation_%'")){while(r.next())tables.add(r.getString(1));}
        for(String target:tables) {
            var columns=new LinkedHashSet<String>();
            try(var s=db.createStatement();var r=s.executeQuery("PRAGMA table_info("+quote(target)+")")){while(r.next())if(namedReference(r.getString("name"),asset))columns.add(r.getString("name"));}
            try(var s=db.createStatement();var r=s.executeQuery("PRAGMA foreign_key_list("+quote(target)+")")){while(r.next())if(asset.equals(r.getString("table")) && (r.getString("to")==null || r.getString("to").equals("id")))columns.add(r.getString("from"));}
            for(String column:columns) {
                String allowed="";Object allowedId=null;
                if(target.equals("residents")){allowed=" AND uuid<>?";allowedId=p.player().toString();}
                else if(target.equals("towns") && asset.equals("civilizations") && p.town()!=null){allowed=" AND id<>?";allowedId=p.town();}
                else if(target.equals("civilizations") && asset.equals("towns") && p.civ()!=null){allowed=" AND id<>?";allowedId=p.civ();}
                else if(target.equals("camp_members") && asset.equals("camps")){allowed=" AND uuid<>?";allowedId=p.player().toString();}
                try(var s=db.prepareStatement("SELECT 1 FROM "+quote(target)+" WHERE "+quote(column)+"=?"+allowed+" LIMIT 1")){s.setLong(1,id);if(allowedId!=null)s.setObject(2,allowedId);try(var r=s.executeQuery()){if(r.next())throw new SQLException("Foundation acquired related records: "+target+"."+column);}}
            }
        }
    }
    private static void restoreTaxState(Connection db,FoundationCreationStore.Plan p)throws SQLException {
        long debt,days;Object civ,at;
        try(var s=db.prepareStatement("SELECT * FROM foundation_creation_resident_state WHERE escrow_id=?")){s.setString(1,p.id());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Foundation has no resident compensation snapshot");debt=r.getLong("debt");days=r.getLong("eviction_days");civ=r.getObject("cooldown_civ");at=r.getObject("cooldown_at");}}
        try(var s=db.prepareStatement("UPDATE residents SET debt=?,days_til_evict=? WHERE uuid=?")){s.setLong(1,debt);s.setLong(2,days);s.setString(3,p.player().toString());s.executeUpdate();}
        if(table(db,"resident_rejoin_cooldowns")) {
            try(var s=db.prepareStatement("DELETE FROM resident_rejoin_cooldowns WHERE player_uuid=?")){s.setString(1,p.player().toString());s.executeUpdate();}
            if(civ!=null)try(var s=db.prepareStatement("INSERT INTO resident_rejoin_cooldowns(player_uuid,civ_id,departed_at) VALUES(?,?,?)")){s.setString(1,p.player().toString());s.setObject(2,civ);s.setObject(3,at);s.executeUpdate();}
        }
    }
    private static void requireUnchangedTaxState(Connection db,FoundationCreationStore.Plan p)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM residents r JOIN foundation_creation_resident_state f ON f.escrow_id=? WHERE r.uuid=? AND r.debt=f.debt AND r.days_til_evict=f.eviction_days")) {
            s.setString(1,p.id());s.setString(2,p.player().toString());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Foundation resident tax state changed");}
        }
        if(table(db,"resident_rejoin_cooldowns"))try(var s=db.prepareStatement("""
            SELECT 1 FROM foundation_creation_resident_state f
            LEFT JOIN resident_rejoin_cooldowns c ON c.player_uuid=?
            WHERE f.escrow_id=? AND c.civ_id IS f.cooldown_civ AND c.departed_at IS f.cooldown_at
            """)) {
            s.setString(1,p.player().toString());s.setString(2,p.id());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Foundation resident rejoin state changed");}
        }
    }
    public static void cancelIncomplete(Connection db,String id)throws SQLException {
        transaction(db);if(completed(db,id))return;
        var p=FoundationCreationStore.find(db,id);
        if(p==null || !p.state().equals("CREATING") || p.fee()!=0 || p.ready() || p.build()!=null)throw new SQLException("Foundation is not an incomplete unpaid creation");
        if(p.camp()!=null){requireSnapshot(db,id,"camps",p.camp());requireSnapshot(db,id,"camp_members",p.camp());}
        if(p.town()!=null)requireSnapshot(db,id,"towns",p.town());
        if(p.civ()!=null)requireSnapshot(db,id,"civilizations",p.civ());
        requireNoOtherReferences(db,p,"camps",p.camp());requireNoOtherReferences(db,p,"towns",p.town());requireNoOtherReferences(db,p,"civilizations",p.civ());
        Long camp=p.camp()!=null?p.camp():p.beforeCamp(),civ=p.civ()!=null?p.civ():p.beforeCiv(),town=!p.nativeSettler() && p.town()!=null?p.town():p.beforeTown();
        try(var s=db.prepareStatement("SELECT 1 FROM residents WHERE uuid=? AND camp_id IS ? AND civ_id IS ? AND town_id IS ?")){s.setString(1,p.player().toString());s.setObject(2,camp);s.setObject(3,civ);s.setObject(4,town);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Incomplete foundation membership changed");}}
        boolean townChanged=!Objects.equals(town,p.beforeTown());
        if(townChanged)requireUnchangedTaxState(db,p);
        // Avoid membership cleanup triggers when the native settler never moved.
        if(!Objects.equals(camp,p.beforeCamp()) || !Objects.equals(civ,p.beforeCiv()) || !Objects.equals(town,p.beforeTown())) {
            try(var s=db.prepareStatement("UPDATE residents SET camp_id=?,civ_id=?,town_id=?,updated_at=? WHERE uuid=?")){s.setObject(1,p.beforeCamp());s.setObject(2,p.beforeCiv());s.setObject(3,p.beforeTown());s.setLong(4,System.currentTimeMillis());s.setString(5,p.player().toString());if(s.executeUpdate()!=1)throw new SQLException("Foundation founder missing");}
            if(townChanged)restoreTaxState(db,p);
        }
        if(p.camp()!=null)try(var s=db.prepareStatement("DELETE FROM camp_members WHERE camp_id=? AND uuid=?")){s.setLong(1,p.camp());s.setString(2,p.player().toString());s.executeUpdate();}
        delete(db,"towns",p.town());delete(db,"civilizations",p.civ());delete(db,"camps",p.camp());
        try(var s=db.prepareStatement("INSERT INTO foundation_creation_rollbacks VALUES(?,?)")){s.setString(1,id);s.setLong(2,System.currentTimeMillis());s.executeUpdate();}
        FoundationEscrowStore.cancel(db,id,true);
    }
    private static void delete(Connection db,String table,Long id)throws SQLException {
        if(id==null)return;try(var s=db.prepareStatement("DELETE FROM "+quote(table)+" WHERE id=?")){s.setLong(1,id);if(s.executeUpdate()!=1)throw new SQLException("Foundation asset vanished during compensation");}
    }
}
