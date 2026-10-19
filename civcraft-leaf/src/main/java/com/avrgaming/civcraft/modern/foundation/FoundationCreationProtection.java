package com.avrgaming.civcraft.modern.foundation;

import java.sql.*;
import java.util.*;

/** Database guards also cover the camp service's separate SQLite connection. */
public final class FoundationCreationProtection {
    private FoundationCreationProtection() {}
    private static String quote(String name){return "\""+name.replace("\"","\"\"")+"\"";}
    private static void transaction(Connection db)throws SQLException {if(db.getAutoCommit())throw new SQLException("Foundation write permit requires a transaction");}
    private static boolean installed(Connection db)throws SQLException {
        try(var s=db.createStatement();var r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='foundation_creation_write_permits'")){return r.next();}
    }
    /** Called before schema initialization, with gameplay still disabled. */
    public static void suspendForBootstrap(Connection db)throws SQLException {
        var names=new ArrayList<String>();try(var s=db.createStatement();var r=s.executeQuery("SELECT name FROM sqlite_master WHERE type='trigger' AND substr(name,1,17)='foundation_guard_'")){while(r.next())names.add(r.getString(1));}
        for(String name:names)try(var s=db.createStatement()){s.executeUpdate("DROP TRIGGER "+quote(name));}
        if(installed(db))try(var s=db.createStatement()){s.executeUpdate("DELETE FROM foundation_creation_write_permits");}
    }
    private static String asset(String column) {
        if(column.equals("camp") || column.equals("camp_id") || column.endsWith("_camp_id") || column.endsWith("_camp"))return "created_camp";
        if(column.equals("town") || column.equals("town_id") || column.endsWith("_town_id") || column.endsWith("_town"))return "created_town";
        if(column.equals("civ") || column.equals("civilization") || column.equals("civ_id") || column.equals("civilization_id") || column.endsWith("_civ_id") || column.endsWith("_civ"))return "created_civ";
        if(column.equals("build_id") || column.endsWith("_build_id"))return "created_build";
        return null;
    }
    private static String parent(String table){return switch(table){case "camps"->"created_camp";case "towns"->"created_town";case "civilizations"->"created_civ";case "structure_builds"->"created_build";default->null;};}
    record TaggedReference(String idColumn,String kindColumn,String kind,String asset) {}
    static List<TaggedReference> taggedReferences(String table) {
        return switch(table) {
            case "settlement_invites" -> List.of(new TaggedReference("settlement_id","kind","CIV","created_civ"),new TaggedReference("settlement_id","kind","TOWN","created_town"));
            case "upkeep_payments" -> List.of(new TaggedReference("target_id","kind","civ","created_civ"),new TaggedReference("target_id","kind","town","created_town"));
            default -> List.of();
        };
    }
    public static void install(Connection db)throws SQLException {
        transaction(db);suspendForBootstrap(db);
        try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS foundation_creation_write_permits(escrow_id TEXT PRIMARY KEY REFERENCES foundation_escrows(id),purpose TEXT NOT NULL CHECK(purpose IN ('REGISTER','COMMIT','ROLLBACK')))");}
        var tables=new ArrayList<String>();try(var s=db.createStatement();var r=s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'foundation_%'")){while(r.next())tables.add(r.getString(1));}
        for(String table:tables) {
            var columns=new ArrayList<String>();var references=new LinkedHashMap<String,String>();
            try(var s=db.createStatement();var r=s.executeQuery("PRAGMA table_info("+quote(table)+")")){while(r.next()){String column=r.getString("name");columns.add(column);String asset=asset(column);if(asset!=null)references.put(column,asset);}}
            try(var s=db.createStatement();var r=s.executeQuery("PRAGMA foreign_key_list("+quote(table)+")")){while(r.next()){String asset=parent(r.getString("table"));if(asset!=null && (r.getString("to")==null || r.getString("to").equals("id")))references.put(r.getString("from"),asset);}}
            String root=parent(table);if(root!=null)references.put("id",root);
            if(references.isEmpty() && taggedReferences(table).isEmpty() && !table.equals("residents"))continue;
            for(String operation:List.of("INSERT","UPDATE","DELETE")) {
                var relations=new ArrayList<String>();
                for(var reference:references.entrySet()) {
                    if(!operation.equals("DELETE"))relations.add("NEW."+quote(reference.getKey())+"=p."+reference.getValue());
                    if(!operation.equals("INSERT"))relations.add("OLD."+quote(reference.getKey())+"=p."+reference.getValue());
                }
                for(var reference:taggedReferences(table))for(String row:operation.equals("INSERT")?List.of("NEW"):operation.equals("DELETE")?List.of("OLD"):List.of("OLD","NEW"))
                    relations.add("("+row+"."+quote(reference.kindColumn())+"='"+reference.kind()+"' AND "+row+"."+quote(reference.idColumn())+"=p."+reference.asset()+")");
                if(table.equals("residents"))relations.add("e.player_uuid="+(operation.equals("INSERT")?"NEW":"OLD")+".uuid");
                if(relations.isEmpty())continue;
                String changed="";
                if(operation.equals("UPDATE")) {
                    var differences=new ArrayList<String>();
                    for(String column:columns)if(!table.equals("residents") || List.of("uuid","camp_id","civ_id","town_id").contains(column))differences.add("NEW."+quote(column)+" IS NOT OLD."+quote(column));
                    changed="("+String.join(" OR ",differences)+") AND ";
                }
                String condition=changed+"EXISTS(SELECT 1 FROM foundation_creation_plans p JOIN foundation_escrows e ON e.id=p.escrow_id WHERE e.state='CREATING' AND ("+String.join(" OR ",relations)+") AND NOT EXISTS(SELECT 1 FROM foundation_creation_write_permits w WHERE w.escrow_id=p.escrow_id))";
                try(var s=db.createStatement()){s.executeUpdate("CREATE TRIGGER "+quote("foundation_guard_"+table+"_"+operation.toLowerCase(Locale.ROOT))+" BEFORE "+operation+" ON "+quote(table)+" WHEN "+condition+" BEGIN SELECT RAISE(ABORT,'Foundation creation is pending'); END");}
            }
        }
    }
    public static void permit(Connection db,String id,String purpose)throws SQLException {
        transaction(db);if(!installed(db))return;
        try(var s=db.prepareStatement("INSERT INTO foundation_creation_write_permits VALUES(?,?)")){s.setString(1,id);s.setString(2,purpose);s.executeUpdate();}
    }
    public static void release(Connection db,String id)throws SQLException {
        transaction(db);if(!installed(db))return;
        try(var s=db.prepareStatement("DELETE FROM foundation_creation_write_permits WHERE escrow_id=?")){s.setString(1,id);s.executeUpdate();}
    }
    public static void register(Connection db,UUID actor)throws SQLException {
        transaction(db);if(!installed(db))return;
        try(var s=db.prepareStatement("INSERT INTO foundation_creation_write_permits SELECT p.escrow_id,'REGISTER' FROM foundation_creation_plans p JOIN foundation_escrows e ON e.id=p.escrow_id WHERE e.player_uuid=? AND e.state='CREATING'")){s.setString(1,actor.toString());s.executeUpdate();}
    }
    public static void registered(Connection db,UUID actor)throws SQLException {
        transaction(db);if(!installed(db))return;
        try(var s=db.prepareStatement("DELETE FROM foundation_creation_write_permits WHERE purpose='REGISTER' AND escrow_id IN (SELECT id FROM foundation_escrows WHERE player_uuid=?)")){s.setString(1,actor.toString());s.executeUpdate();}
    }
    public static boolean townHeld(Connection db,long town)throws SQLException {
        if(!FoundationCreationStore.installed(db))return false;
        try(var s=db.prepareStatement("SELECT 1 FROM foundation_creation_plans p JOIN foundation_escrows e ON e.id=p.escrow_id WHERE p.created_town=? AND e.state='CREATING'")){s.setLong(1,town);try(var r=s.executeQuery()){return r.next();}}
    }
    public static boolean civilizationHeld(Connection db,long civ)throws SQLException {
        if(!FoundationCreationStore.installed(db))return false;
        try(var s=db.prepareStatement("SELECT 1 FROM foundation_creation_plans p JOIN foundation_escrows e ON e.id=p.escrow_id WHERE p.created_civ=? AND e.state='CREATING'")){s.setLong(1,civ);try(var r=s.executeQuery()){return r.next();}}
    }
    /** Check before applying any part of a war result; keep that result queued. */
    public static boolean settlementChangeHeld(Connection db,Long town,Long civ)throws SQLException {
        if(!FoundationCreationStore.installed(db))return false;
        try(var s=db.prepareStatement("""
            SELECT 1 FROM foundation_escrows e LEFT JOIN foundation_creation_plans p ON p.escrow_id=e.id
            LEFT JOIN residents r ON r.uuid=e.player_uuid WHERE e.state='CREATING' AND
            (p.created_town=? OR r.town_id=? OR p.created_civ=? OR r.civ_id=?) LIMIT 1
            """)) {
            s.setObject(1,town);s.setObject(2,town);s.setObject(3,civ);s.setObject(4,civ);try(var r=s.executeQuery()){return r.next();}
        }
    }
    /** Safe to append before the foundation schema has been installed. */
    public static String visible(Connection db,String table,String id)throws SQLException {
        if(!id.matches("[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*"))throw new SQLException("Foundation visibility requires a qualified column");
        if(!FoundationCreationStore.installed(db))return "1=1";
        String column=parent(table);if(column==null)throw new SQLException("Unknown foundation asset table");
        return "NOT EXISTS(SELECT 1 FROM foundation_creation_plans fp JOIN foundation_escrows fe ON fe.id=fp.escrow_id WHERE fp."+column+"="+id+" AND fe.state='CREATING')";
    }
    public static void requirePlayerAvailable(Connection db,UUID player)throws SQLException {
        if(!FoundationCreationStore.installed(db))return;
        try(var s=db.prepareStatement("""
            SELECT 1 FROM foundation_escrows e LEFT JOIN foundation_creation_plans p ON e.id=p.escrow_id
            JOIN residents r ON r.uuid=? WHERE e.state='CREATING' AND
            (e.player_uuid=r.uuid OR p.created_camp=r.camp_id OR p.created_town=r.town_id OR p.created_civ=r.civ_id)
            """)) {
            s.setString(1,player.toString());try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Основание поселения ещё восстанавливается. Дождитесь завершения операции.");}
        }
    }
    public static String residentVisible(Connection db,String uuid)throws SQLException {
        if(!uuid.matches("[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*"))throw new SQLException("Foundation visibility requires a qualified resident column");
        if(!FoundationEscrowStore.installed(db))return "1=1";
        return "NOT EXISTS(SELECT 1 FROM foundation_escrows fe WHERE fe.player_uuid="+uuid+" AND fe.state='CREATING')";
    }
}
