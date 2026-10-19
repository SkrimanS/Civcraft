package com.avrgaming.civcraft.modern.town;

import java.sql.*;

public final class BarracksStore {
    private BarracksStore() {}
    public record Job(long build,String unit,double required,double progress,String state) {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS barracks_selection(build_id INTEGER PRIMARY KEY REFERENCES structure_builds(id) ON DELETE CASCADE,unit TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS barracks_training(build_id INTEGER PRIMARY KEY REFERENCES structure_builds(id) ON DELETE CASCADE,unit TEXT NOT NULL,required REAL NOT NULL CHECK(required>0),progress REAL NOT NULL CHECK(progress>=0),state TEXT NOT NULL CHECK(state IN ('TRAINING','DELIVERING','COMPLETE')),operation TEXT)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS civ_settler_training_counts(civ_id INTEGER PRIMARY KEY REFERENCES civilizations(id) ON DELETE CASCADE,amount INTEGER NOT NULL CHECK(amount>=0))");
        }
    }
    public static Job job(Connection db,long build)throws SQLException {
        try(var s=db.prepareStatement("SELECT * FROM barracks_training WHERE build_id=?")){s.setLong(1,build);try(var r=s.executeQuery()){return r.next()?new Job(build,r.getString("unit"),r.getDouble("required"),r.getDouble("progress"),r.getString("state")):null;}}
    }
    public static long settlers(Connection db,long civ)throws SQLException {
        try(var s=db.prepareStatement("SELECT amount FROM civ_settler_training_counts WHERE civ_id=?")){s.setLong(1,civ);try(var r=s.executeQuery()){return r.next()?r.getLong(1):0;}}
    }
    public static void start(Connection db,long build,long town,long civ,String unit,double required,long cost,double initial)throws SQLException {
        ProductionStore.requireBuildUnlocked(db,build);var previous=job(db,build);if(previous!=null && !previous.state().equals("COMPLETE"))throw new IllegalArgumentException("В казарме уже идёт обучение.");
        try(var s=db.prepareStatement("UPDATE towns SET coins=coins-? WHERE id=? AND coins>=?")){s.setLong(1,cost);s.setLong(2,town);s.setLong(3,cost);if(s.executeUpdate()!=1)throw new IllegalArgumentException("В казне недостаточно монет: требуется "+cost+".");}
        try(var s=db.prepareStatement("INSERT INTO barracks_training VALUES(?,?,?,?, 'TRAINING',NULL) ON CONFLICT(build_id) DO UPDATE SET unit=excluded.unit,required=excluded.required,progress=excluded.progress,state='TRAINING',operation=NULL")){s.setLong(1,build);s.setString(2,unit);s.setDouble(3,required);s.setDouble(4,Math.min(required,Math.max(0,initial)));s.executeUpdate();}
        if(unit.equals("u_settler"))try(var s=db.prepareStatement("INSERT INTO civ_settler_training_counts VALUES(?,1) ON CONFLICT(civ_id) DO UPDATE SET amount=amount+1")){s.setLong(1,civ);s.executeUpdate();}
    }
    public static void advance(Connection db,long build,double hammers)throws SQLException {
        if(!Double.isFinite(hammers) || hammers<0)throw new IllegalArgumentException("Неверная выработка обучения");
        try(var s=db.prepareStatement("UPDATE barracks_training SET progress=MIN(required,progress+?) WHERE build_id=? AND state='TRAINING'")){s.setDouble(1,hammers);s.setLong(2,build);s.executeUpdate();}
    }
    public static void full(Connection db,long build)throws SQLException {
        try(var s=db.prepareStatement("UPDATE barracks_training SET progress=MAX(0,required-20) WHERE build_id=? AND state='TRAINING'")){s.setLong(1,build);s.executeUpdate();}
    }
    public static void delivering(Connection db,long build,String operation)throws SQLException {
        try(var s=db.prepareStatement("UPDATE barracks_training SET state='DELIVERING',operation=? WHERE build_id=? AND state='TRAINING' AND progress>=required")){s.setString(1,operation);s.setLong(2,build);if(s.executeUpdate()!=1)throw new SQLException("Training changed before delivery");}
    }
    public static void complete(Connection db,String operation)throws SQLException {
        try(var s=db.prepareStatement("UPDATE barracks_training SET state='COMPLETE',operation=NULL,progress=0 WHERE operation=? AND state='DELIVERING'")){s.setString(1,operation);s.executeUpdate();}
    }
}
