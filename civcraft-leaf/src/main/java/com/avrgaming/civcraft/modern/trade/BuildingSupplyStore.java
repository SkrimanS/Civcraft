package com.avrgaming.civcraft.modern.trade;

import java.sql.*;
import java.util.*;

/** Four researched stock lines; prices are for a full stack, as in the original Store. */
public final class BuildingSupplyStore {
    private BuildingSupplyStore() {}
    public record Offer(int item,int data,String name,long price) {
        public static Offer parse(String value) {
            String[] parts=value.split(",",-1);
            if(parts.length!=4)throw new IllegalArgumentException("Некорректный ассортимент магазина.");
            int item=Integer.parseInt(parts[0].trim()),data=Integer.parseInt(parts[1].trim());
            double price=Double.parseDouble(parts[3].trim());
            if(item<=0 || data<0 || data>255 || !Double.isFinite(price) || price<0 || price!=Math.rint(price) || price>1_000_000_000_000L)throw new IllegalArgumentException("Некорректная цена магазина.");
            String name=switch(item){case 88->"Песок душ";case 87->"Незерак";case 405->"Адский кирпич";case 406->"Кварц";case 369->"Стержень ифрита";case 378->"Сгусток магмы";case 348->"Светящаяся пыль";default->parts[2].trim();};
            return new Offer(item,data,name,(long)price);
        }
    }
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS town_supply_settings(town_id INTEGER PRIMARY KEY REFERENCES towns(id) ON DELETE CASCADE,fee REAL NOT NULL DEFAULT 0.05 CHECK(fee BETWEEN 0 AND 1))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS town_supply_offers(town_id INTEGER NOT NULL REFERENCES towns(id) ON DELETE CASCADE,slot INTEGER NOT NULL CHECK(slot BETWEEN 0 AND 3),item INTEGER NOT NULL,data INTEGER NOT NULL,name TEXT NOT NULL,price INTEGER NOT NULL CHECK(price>=0),PRIMARY KEY(town_id,slot))");
        }
    }
    public static Offer offer(Connection db,long town,int slot)throws SQLException {
        if(slot<0 || slot>3)throw new IllegalArgumentException("Некорректное место магазина.");
        try(var s=db.prepareStatement("SELECT item,data,name,price FROM town_supply_offers WHERE town_id=? AND slot=?")){s.setLong(1,town);s.setInt(2,slot);try(var r=s.executeQuery()){return r.next()?new Offer(r.getInt(1),r.getInt(2),r.getString(3),r.getLong(4)):null;}}
    }
    public static void add(Connection db,long town,Offer offer)throws SQLException {
        int slot;try(var s=db.prepareStatement("SELECT COALESCE(MAX(slot)+1,0) FROM town_supply_offers WHERE town_id=?")){s.setLong(1,town);try(var r=s.executeQuery()){r.next();slot=r.getInt(1);}}
        if(slot>=4)throw new IllegalArgumentException("Все четыре места магазина заняты. /town reset store освобождает ассортимент без возврата монет.");
        try(var s=db.prepareStatement("INSERT INTO town_supply_offers VALUES(?,?,?,?,?,?)")){s.setLong(1,town);s.setInt(2,slot);s.setInt(3,offer.item());s.setInt(4,offer.data());s.setString(5,offer.name());s.setLong(6,offer.price());s.executeUpdate();}
    }
    public static double fee(Connection db,long town)throws SQLException {
        try(var s=db.prepareStatement("SELECT fee FROM town_supply_settings WHERE town_id=?")){s.setLong(1,town);try(var r=s.executeQuery()){return r.next()?r.getDouble(1):.05;}}
    }
    public static void reset(Connection db,long town,UUID actor,Collection<String> ids)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM towns t JOIN civilizations c ON c.id=t.civ_id WHERE t.id=? AND (t.mayor_uuid=? OR c.leader_uuid=? OR EXISTS(SELECT 1 FROM town_assistants a WHERE a.town_id=t.id AND a.player_uuid=?))")){s.setLong(1,town);for(int i=2;i<=4;i++)s.setString(i,actor.toString());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Сброс магазина доступен мэру, помощнику или лидеру цивилизации.");}}
        try(var s=db.prepareStatement("SELECT 1 FROM structure_builds WHERE town_id=? AND structure_id='s_store' AND status='COMPLETE'")){s.setLong(1,town);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("В городе нет завершённого магазина.");}}
        try(var s=db.prepareStatement("DELETE FROM town_supply_offers WHERE town_id=?")){s.setLong(1,town);s.executeUpdate();}
        try(var s=db.prepareStatement("DELETE FROM town_upgrades WHERE town_id=? AND upgrade_id=?")){for(String id:ids){s.setLong(1,town);s.setString(2,id);s.addBatch();}s.executeBatch();}
    }
}
