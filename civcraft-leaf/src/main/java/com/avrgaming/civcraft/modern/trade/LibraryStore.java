package com.avrgaming.civcraft.modern.trade;

import java.sql.*;

/** Four ordered sign offers per town, independent of the research prerequisite level. */
public final class LibraryStore {
    private LibraryStore() {}
    public static void reset(Connection db,long town,java.util.UUID actor,java.util.Collection<String> researchIds)throws SQLException {
        try(var s=db.prepareStatement("SELECT t.mayor_uuid,c.leader_uuid,EXISTS(SELECT 1 FROM town_assistants a WHERE a.town_id=t.id AND a.player_uuid=?) FROM towns t JOIN civilizations c ON c.id=t.civ_id WHERE t.id=?")) {
            s.setString(1,actor.toString());s.setLong(2,town);
            try(var r=s.executeQuery()){if(!r.next() || (!actor.toString().equals(r.getString(1)) && !actor.toString().equals(r.getString(2)) && !r.getBoolean(3)))throw new IllegalArgumentException("Сброс библиотеки доступен мэру, помощнику или лидеру цивилизации.");}
        }
        try(var s=db.prepareStatement("SELECT 1 FROM structure_builds WHERE town_id=? AND structure_id='s_library' AND status='COMPLETE'")){s.setLong(1,town);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("В городе нет завершённой библиотеки.");}}
        try(var s=db.prepareStatement("DELETE FROM town_library_offers WHERE town_id=?")){s.setLong(1,town);s.executeUpdate();}
        try(var s=db.prepareStatement("DELETE FROM town_upgrades WHERE town_id=? AND upgrade_id=?")){for(String id:researchIds){s.setLong(1,town);s.setString(2,id);s.addBatch();}s.executeBatch();}
    }
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS town_library_settings(town_id INTEGER PRIMARY KEY REFERENCES towns(id) ON DELETE CASCADE,level INTEGER NOT NULL DEFAULT 1 CHECK(level BETWEEN 1 AND 4),fee REAL NOT NULL DEFAULT 0.05 CHECK(fee BETWEEN 0 AND 1))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS town_library_offers(town_id INTEGER NOT NULL REFERENCES towns(id) ON DELETE CASCADE,slot INTEGER NOT NULL CHECK(slot BETWEEN 0 AND 3),enchantment TEXT NOT NULL,level INTEGER NOT NULL,price INTEGER NOT NULL CHECK(price>=0),PRIMARY KEY(town_id,slot))");
        }
    }
    public static void level(Connection db,long town,int level)throws SQLException {
        if(level<1 || level>4)throw new IllegalArgumentException("Некорректный уровень библиотеки.");
        try(var s=db.prepareStatement("INSERT INTO town_library_settings(town_id,level) VALUES(?,?) ON CONFLICT(town_id) DO UPDATE SET level=MAX(level,excluded.level)")){s.setLong(1,town);s.setInt(2,level);s.executeUpdate();}
    }
    public static double fee(Connection db,long town)throws SQLException {
        try(var s=db.prepareStatement("SELECT fee FROM town_library_settings WHERE town_id=?")){s.setLong(1,town);try(var r=s.executeQuery()){return r.next()?r.getDouble(1):.05;}}
    }
    public static LibraryOffer offer(Connection db,long town,int slot)throws SQLException {
        if(slot<0 || slot>3)throw new IllegalArgumentException("Некорректное место библиотеки.");
        try(var s=db.prepareStatement("SELECT enchantment,level,price FROM town_library_offers WHERE town_id=? AND slot=?")) {
            s.setLong(1,town);s.setInt(2,slot);try(var r=s.executeQuery()){return r.next()?new LibraryOffer(r.getString(1),r.getInt(2),r.getLong(3)):null;}
        }
    }
    public static void add(Connection db,long town,LibraryOffer offer)throws SQLException {
        int slot;
        try(var s=db.prepareStatement("SELECT COALESCE(MAX(slot)+1,0) FROM town_library_offers WHERE town_id=?")){s.setLong(1,town);try(var r=s.executeQuery()){r.next();slot=r.getInt(1);}}
        if(slot>=4)throw new IllegalArgumentException("Все четыре места библиотеки заняты.");
        try(var s=db.prepareStatement("INSERT INTO town_library_offers VALUES(?,?,?,?,?)")){s.setLong(1,town);s.setInt(2,slot);s.setString(3,offer.enchantment());s.setInt(4,offer.level());s.setLong(5,offer.price());s.executeUpdate();}
    }
}
