package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.util.*;
public final class OccupationActionsStore {
    private OccupationActionsStore() {}
    public record Liberation(String town,String mother,int towns,boolean civilization) {}
    public record Revolution(long capital,long mother,String name,RevolutionRules.Fee fee,long conquered) {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS occupation_actions(id INTEGER PRIMARY KEY AUTOINCREMENT,action TEXT NOT NULL,actor TEXT NOT NULL,town_id INTEGER NOT NULL,mother_civ INTEGER NOT NULL,owner_civ INTEGER NOT NULL,towns INTEGER NOT NULL,at INTEGER NOT NULL)");}
    }
    private static void peaceTime(Connection db,long now)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM war_sessions WHERE state='ACTIVE' AND start_at<=?")){s.setLong(1,now);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Во время боевого периода освобождение запрещено.");}}
    }
    static long capital(Connection db,long mother)throws SQLException {
        try(var s=db.prepareStatement("SELECT COALESCE(c.capital_town_id,(SELECT b.town_id FROM structure_builds b JOIN towns t ON t.id=b.town_id LEFT JOIN town_conquests tc ON tc.town_id=t.id WHERE b.structure_id='s_capitol' AND b.status='COMPLETE' AND (t.civ_id=c.id OR tc.mother_civ=c.id) ORDER BY b.id LIMIT 1),(SELECT MIN(t.id) FROM towns t LEFT JOIN town_conquests tc ON tc.town_id=t.id WHERE t.civ_id=c.id OR tc.mother_civ=c.id)) FROM civilizations c WHERE c.id=?")) {
            s.setLong(1,mother);try(var r=s.executeQuery()){if(!r.next() || r.getObject(1)==null)throw new IllegalArgumentException("Исходная столица не найдена.");return r.getLong(1);}
        }
    }
    private static void move(Connection db,long town,long civ,long now)throws SQLException {
        com.avrgaming.civcraft.modern.wall.WallStore.requireTownUnlocked(db,town);
        com.avrgaming.civcraft.modern.trade.TradeShipOperations.requireTownUnlocked(db,town);
        com.avrgaming.civcraft.modern.town.ProductionStore.requireTownUnlocked(db,town);
        try(var s=db.prepareStatement("UPDATE towns SET civ_id=? WHERE id=?")){s.setLong(1,civ);s.setLong(2,town);if(s.executeUpdate()!=1)throw new SQLException("Liberation town disappeared");}
        try(var s=db.prepareStatement("UPDATE town_claims SET civ_id=? WHERE town_id=?")){s.setLong(1,civ);s.setLong(2,town);s.executeUpdate();}
        try(var s=db.prepareStatement("UPDATE residents SET civ_id=?,updated_at=? WHERE town_id=?")){s.setLong(1,civ);s.setLong(2,now);s.setLong(3,town);s.executeUpdate();}
        OutlawStore.removeNewCivilizationMembers(db,town,civ);
        try(var s=db.prepareStatement("UPDATE town_conquests SET mother_civ=NULL,changed_at=? WHERE town_id=?")){s.setLong(1,now);s.setLong(2,town);s.executeUpdate();}
    }
    public static Revolution revolutionOffer(Connection db,UUID actor,RevolutionRules rules,WarCalendar calendar,long now)throws SQLException {
        WarClockStore.requireDiplomacyOpen(db,calendar,now);
        if(calendar.declarationClosed(java.time.Instant.ofEpochMilli(now)))throw new IllegalArgumentException("Слишком близко к боевому периоду: восстание запрещено.");
        long town,mother,conquered;String name;
        try(var s=db.prepareStatement("SELECT t.id,tc.mother_civ,c.name,cc.conquered_at FROM residents r JOIN towns t ON t.id=r.town_id AND t.civ_id=r.civ_id LEFT JOIN town_conquests tc ON tc.town_id=t.id LEFT JOIN civilizations c ON c.id=tc.mother_civ LEFT JOIN civ_conquests cc ON cc.civ_id=c.id WHERE r.uuid=?")){s.setString(1,actor.toString());try(var r=s.executeQuery()) {
            if(!r.next())throw new IllegalArgumentException("Для восстания нужно состоять в захваченном городе.");
            if(r.getObject(2)==null || r.getObject(4)==null)throw new IllegalArgumentException("Исходная цивилизация не завоёвана.");town=r.getLong(1);mother=r.getLong(2);name=r.getString(3);conquered=r.getLong(4);
        }}
        if(capital(db,mother)!=town)throw new IllegalArgumentException("Восстание начинается только в исходной столице.");
        if(!rules.cooldownPassed(conquered,now,calendar.zone()))throw new IllegalArgumentException("После завоевания нужно подождать "+rules.cooldownDays()+" дня до восстания.");
        return new Revolution(town,mother,name,rules.fee(db,mother),conquered);
    }
    public static Revolution revolution(Connection db,UUID actor,RevolutionRules rules,WarCalendar calendar,long now)throws SQLException {
        var offer=revolutionOffer(db,actor,rules,calendar,now);
        try(var s=db.prepareStatement("UPDATE towns SET coins=coins-? WHERE id=? AND coins>=?")){s.setLong(1,offer.fee().coins());s.setLong(2,offer.capital());s.setLong(3,offer.fee().coins());if(s.executeUpdate()!=1)throw new IllegalArgumentException("В казне столицы недостаточно монет: требуется "+offer.fee().coins()+".");}
        record Captured(long town,long owner) {}
        var towns=new ArrayList<Captured>();try(var s=db.prepareStatement("SELECT t.id,t.civ_id FROM towns t JOIN town_conquests tc ON tc.town_id=t.id WHERE tc.mother_civ=? ORDER BY t.id")){s.setLong(1,offer.mother());try(var r=s.executeQuery()){while(r.next())towns.add(new Captured(r.getLong(1),r.getLong(2)));}}
        try(var s=db.prepareStatement("DELETE FROM civ_conquests WHERE civ_id=?")){s.setLong(1,offer.mother());s.executeUpdate();}
        var owners=new LinkedHashSet<Long>();for(var city:towns){owners.add(city.owner());move(db,city.town(),offer.mother(),now);}
        for(long owner:owners)if(owner!=offer.mother()) {
            try(var s=db.prepareStatement("INSERT INTO diplomacy_relations(first_civ,second_civ,status,aggressor,changed_at) VALUES(?,?,'WAR',?,?) ON CONFLICT(first_civ,second_civ) DO UPDATE SET status='WAR',aggressor=excluded.aggressor,changed_at=excluded.changed_at")){s.setLong(1,Math.min(owner,offer.mother()));s.setLong(2,Math.max(owner,offer.mother()));s.setLong(3,owner);s.setLong(4,now);s.executeUpdate();}
            try(var s=db.prepareStatement("UPDATE diplomacy_requests SET state='CANCELLED' WHERE state='PENDING' AND ((from_civ=? AND to_civ=?) OR (from_civ=? AND to_civ=?))")){s.setLong(1,owner);s.setLong(2,offer.mother());s.setLong(3,offer.mother());s.setLong(4,owner);s.executeUpdate();}
        }
        try(var s=db.prepareStatement("INSERT INTO occupation_actions(action,actor,town_id,mother_civ,owner_civ,towns,at) VALUES('REVOLUTION',?,?,?,?,?,?)")){s.setString(1,actor.toString());s.setLong(2,offer.capital());s.setLong(3,offer.mother());s.setLong(4,towns.getFirst().owner());s.setInt(5,towns.size());s.setLong(6,now);s.executeUpdate();}return offer;
    }
    public static Liberation liberate(Connection db,String name,UUID actor,long now)throws SQLException {
        peaceTime(db,now);long town,owner,mother;String townName,motherName;
        try(var s=db.prepareStatement("SELECT t.id,t.civ_id,tc.mother_civ,t.name,m.name,c.leader_uuid,r.civ_id FROM towns t JOIN civilizations c ON c.id=t.civ_id LEFT JOIN town_conquests tc ON tc.town_id=t.id LEFT JOIN civilizations m ON m.id=tc.mother_civ LEFT JOIN residents r ON r.uuid=? WHERE t.name=? COLLATE NOCASE")) {
            s.setString(1,actor.toString());s.setString(2,name);try(var r=s.executeQuery()) {
                if(!r.next())throw new IllegalArgumentException("Город не найден: "+name);
                town=r.getLong(1);owner=r.getLong(2);
                if(!actor.toString().equals(r.getString(6)) || r.getObject(7)==null || r.getLong(7)!=owner || ConquestStore.conquered(db,owner))throw new IllegalArgumentException("Освободить город может только лидер владеющей им цивилизации.");
                if(r.getObject(3)==null || r.getString(5)==null)throw new IllegalArgumentException("Этот город не захвачен.");
                mother=r.getLong(3);townName=r.getString(4);motherName=r.getString(5);
            }
        }
        boolean whole=capital(db,mother)==town;
        if(!whole && ConquestStore.conquered(db,mother))throw new IllegalArgumentException("Сначала освободите исходную столицу цивилизации.");
        var towns=new ArrayList<Long>();
        if(whole)try(var s=db.prepareStatement("SELECT t.id FROM towns t JOIN town_conquests tc ON tc.town_id=t.id WHERE tc.mother_civ=? AND t.civ_id=? ORDER BY t.id")){s.setLong(1,mother);s.setLong(2,owner);try(var r=s.executeQuery()){while(r.next())towns.add(r.getLong(1));}}
        else towns.add(town);
        if(whole)try(var s=db.prepareStatement("DELETE FROM civ_conquests WHERE civ_id=?")){s.setLong(1,mother);s.executeUpdate();}
        for(long id:towns)move(db,id,mother,now);
        try(var s=db.prepareStatement("INSERT INTO occupation_actions(action,actor,town_id,mother_civ,owner_civ,towns,at) VALUES('LIBERATE',?,?,?,?,?,?)")){s.setString(1,actor.toString());s.setLong(2,town);s.setLong(3,mother);s.setLong(4,owner);s.setInt(5,towns.size());s.setLong(6,now);s.executeUpdate();}
        return new Liberation(townName,motherName,towns.size(),whole);
    }
}
