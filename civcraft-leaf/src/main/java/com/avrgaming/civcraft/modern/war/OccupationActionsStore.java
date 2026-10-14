package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.util.*;
public final class OccupationActionsStore {
    private OccupationActionsStore() {}
    public record Liberation(String town,String mother,int towns,boolean civilization) {}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS occupation_actions(id INTEGER PRIMARY KEY AUTOINCREMENT,action TEXT NOT NULL,actor TEXT NOT NULL,town_id INTEGER NOT NULL,mother_civ INTEGER NOT NULL,owner_civ INTEGER NOT NULL,towns INTEGER NOT NULL,at INTEGER NOT NULL)");}
    }
    private static void peaceTime(Connection db,long now)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM war_sessions WHERE state='ACTIVE' AND start_at<=?")){s.setLong(1,now);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Во время боевого периода освобождение запрещено.");}}
    }
    private static long capital(Connection db,long mother)throws SQLException {
        try(var s=db.prepareStatement("SELECT COALESCE(c.capital_town_id,(SELECT b.town_id FROM structure_builds b JOIN towns t ON t.id=b.town_id LEFT JOIN town_conquests tc ON tc.town_id=t.id WHERE b.structure_id='s_capitol' AND b.status='COMPLETE' AND (t.civ_id=c.id OR tc.mother_civ=c.id) ORDER BY b.id LIMIT 1),(SELECT MIN(t.id) FROM towns t LEFT JOIN town_conquests tc ON tc.town_id=t.id WHERE t.civ_id=c.id OR tc.mother_civ=c.id)) FROM civilizations c WHERE c.id=?")) {
            s.setLong(1,mother);try(var r=s.executeQuery()){if(!r.next() || r.getObject(1)==null)throw new IllegalArgumentException("Исходная столица не найдена.");return r.getLong(1);}
        }
    }
    private static void move(Connection db,long town,long civ,long now)throws SQLException {
        try(var s=db.prepareStatement("UPDATE towns SET civ_id=? WHERE id=?")){s.setLong(1,civ);s.setLong(2,town);if(s.executeUpdate()!=1)throw new SQLException("Liberation town disappeared");}
        try(var s=db.prepareStatement("UPDATE town_claims SET civ_id=? WHERE town_id=?")){s.setLong(1,civ);s.setLong(2,town);s.executeUpdate();}
        try(var s=db.prepareStatement("UPDATE residents SET civ_id=?,updated_at=? WHERE town_id=?")){s.setLong(1,civ);s.setLong(2,now);s.setLong(3,town);s.executeUpdate();}
        try(var s=db.prepareStatement("UPDATE town_conquests SET mother_civ=NULL,changed_at=? WHERE town_id=?")){s.setLong(1,now);s.setLong(2,town);s.executeUpdate();}
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
