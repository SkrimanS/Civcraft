package com.avrgaming.civcraft.modern.war;
import java.sql.*;
public final class AdminCivilizationStore {
    private AdminCivilizationStore() {}
    public static void install(Connection db)throws SQLException {try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS administrative_civilizations(civ_id INTEGER PRIMARY KEY REFERENCES civilizations(id) ON DELETE CASCADE)");}}
    public static void set(Connection db,String name,boolean enabled)throws SQLException {
        long id;try(var s=db.prepareStatement("SELECT id FROM civilizations WHERE name=? COLLATE NOCASE")){s.setString(1,name);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Цивилизация не найдена.");id=r.getLong(1);}}
        try(var s=db.prepareStatement(enabled?"INSERT OR IGNORE INTO administrative_civilizations VALUES(?)":"DELETE FROM administrative_civilizations WHERE civ_id=?")){s.setLong(1,id);s.executeUpdate();}
    }
    public static void checkDistance(Connection db,String world,double x,double y,double z,double required,boolean operator)throws SQLException {
        if(operator||required<=0)return;
        try(var s=db.prepareStatement("SELECT b.x,b.y,b.z FROM administrative_civilizations a JOIN civilizations c ON c.id=a.civ_id JOIN structure_builds b ON b.town_id=c.capital_town_id JOIN towns t ON t.id=b.town_id AND t.civ_id=c.id WHERE b.structure_id='s_capitol' AND b.world=? AND b.status NOT IN ('DEMOLISHED','CANCELLED','WAR_DESTROYED')")) {
            s.setString(1,world);try(var r=s.executeQuery()){while(r.next()){double dx=x-r.getDouble(1),dy=y-r.getDouble(2),dz=z-r.getDouble(3);if(dx*dx+dy*dy+dz*dz<required*required)throw new IllegalArgumentException("Нельзя строить ближе "+required+" блоков от капитолия административной цивилизации.");}}
        }
    }
}
