package com.avrgaming.civcraft.modern.foundation;

import java.sql.*;
import java.util.UUID;
import org.bukkit.Location;

/** A normal resident may found a town; membership changes only after its hall is queued. */
public final class SettlerTownStore {
    private SettlerTownStore() {}
    public static long sourceTown(Connection db,UUID founder,long civ)throws SQLException {
        try(var s=db.prepareStatement("SELECT r.town_id,t.mayor_uuid FROM residents r JOIN towns t ON t.id=r.town_id WHERE r.uuid=? AND r.civ_id=? AND t.civ_id=? AND r.camp_id IS NULL")) {
            s.setString(1,founder.toString());s.setLong(2,civ);s.setLong(3,civ);
            try(var r=s.executeQuery()) {
                if(!r.next())throw new IllegalArgumentException("Поселенец основывает город для жителя своей цивилизации.");
                if(founder.toString().equals(r.getString(2)))throw new IllegalArgumentException("Мэр не может основать другой город. Сначала передайте должность.");
                return r.getLong(1);
            }
        }
    }
    public static boolean authority(Connection db,UUID actor,long civ)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM residents r JOIN civilizations c ON c.id=r.civ_id WHERE r.uuid=? AND c.id=? AND c.leader_uuid=r.uuid")) {
            s.setString(1,actor.toString());s.setLong(2,civ);try(var r=s.executeQuery()){return r.next();}
        }
    }
    public static void requireLocation(Connection db,long civ,Location at,double friendly,double enemy,double landChunks,boolean preliminary,long now)throws SQLException {
        String world=at.getWorld().getName();int x=at.getBlockX(),y=at.getBlockY(),z=at.getBlockZ();
        if(!preliminary) {
            try(var s=db.prepareStatement("SELECT 1 FROM war_sessions w WHERE w.state='ACTIVE' AND w.start_at<=? AND w.end_at>? AND EXISTS(SELECT 1 FROM diplomacy_relations d WHERE d.status='WAR' AND (d.first_civ=? OR d.second_civ=?))")) {
                s.setLong(1,now);s.setLong(2,now);s.setLong(3,civ);s.setLong(4,civ);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Во время боевого периода воюющая цивилизация не может основывать города.");}
            }
            try(var s=db.prepareStatement("SELECT 1 FROM town_claims WHERE world=? AND chunk_x=? AND chunk_z=?")) {
                s.setString(1,world);s.setInt(2,x>>4);s.setInt(3,z>>4);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Нельзя основать город в уже занятом городском чанке.");}
            }
            try(var s=db.prepareStatement("SELECT 1 FROM culture_regions c JOIN towns t ON t.id=c.town_id WHERE c.world=? AND c.chunk_x=? AND c.chunk_z=? AND t.civ_id<>?")) {
                s.setString(1,world);s.setInt(2,x>>4);s.setInt(3,z>>4);s.setLong(4,civ);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Нельзя основать город в культуре другой цивилизации.");}
            }
            try(var s=db.prepareStatement("SELECT chunk_x,chunk_z FROM town_claims WHERE world=? AND civ_id<>?")) {
                s.setString(1,world);s.setLong(2,civ);try(var r=s.executeQuery()){while(r.next()){double dx=(double)(x>>4)-r.getInt(1),dz=(double)(z>>4)-r.getInt(2);if(dx*dx+dz*dz<=landChunks*landChunks)throw new IllegalArgumentException("Слишком близко к землям другой цивилизации. Нужно больше "+landChunks+" чанков.");}}
            }
        }
        try(var s=db.prepareStatement("SELECT b.x,b.y,b.z,b.template_path,t.civ_id,t.name FROM active_structure_builds b JOIN towns t ON t.id=b.town_id WHERE b.world=? AND b.structure_id IN ('s_townhall','s_capitol')")) {
            s.setString(1,world);try(var r=s.executeQuery()){while(r.next()) {
                var offset=com.avrgaming.civcraft.modern.build.LegacyGeometry.center(r.getString(4));
                double dx=r.getInt(1)+offset.x()-x,dy=r.getInt(2)+offset.y()-y,dz=r.getInt(3)+offset.z()-z;
                boolean hostile=!preliminary && com.avrgaming.civcraft.modern.diplomacy.DiplomacyStore.status(db,civ,r.getLong(5))==com.avrgaming.civcraft.modern.diplomacy.DiplomacyStore.Status.WAR;
                double minimum=hostile?enemy:friendly;
                if(dx*dx+dy*dy+dz*dz<minimum*minimum)throw new IllegalArgumentException("Слишком близко к ратуше города «"+r.getString(6)+"». Минимум: "+minimum+" блоков.");
            }}
        }
    }
    public static long create(Connection db,UUID founder,long civ,long previousTown,String name,Location origin,double hammers,long now)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Settler town creation requires a transaction");
        if(sourceTown(db,founder,civ)!=previousTown)throw new IllegalArgumentException("Основатель сменил город. Повторите основание.");
        try(var s=db.prepareStatement("SELECT 1 FROM towns WHERE name=? COLLATE NOCASE")){s.setString(1,name);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Город с таким названием уже существует.");}}
        try(var s=db.prepareStatement("INSERT INTO towns(name,civ_id,mayor_uuid,world,x,y,z,hammers_per_hour,created_at) VALUES(?,?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1,name);s.setLong(2,civ);s.setString(3,founder.toString());s.setString(4,origin.getWorld().getName());s.setInt(5,origin.getBlockX());s.setInt(6,origin.getBlockY());s.setInt(7,origin.getBlockZ());s.setDouble(8,hammers);s.setLong(9,now);s.executeUpdate();
            try(var r=s.getGeneratedKeys()){if(!r.next())throw new SQLException("Missing new settler town id");long id=r.getLong(1);FoundationCreationStore.captureTown(db,id,founder,civ,name,origin.getWorld().getName(),origin.getBlockX(),origin.getBlockY(),origin.getBlockZ());return id;}
        }
    }
    public static void complete(Connection db,UUID founder,long town,long civ,long previousTown,Location center,long now)throws SQLException {
        complete(db,founder,town,civ,previousTown,center.getWorld().getName(),center.getBlockX()>>4,center.getBlockZ()>>4,now);
    }
    public static void complete(Connection db,UUID founder,long town,long civ,long previousTown,String world,int chunkX,int chunkZ,long now)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Settler membership completion requires a transaction");
        if(sourceTown(db,founder,civ)!=previousTown)throw new IllegalArgumentException("Основатель сменил город. Повторите основание.");
        try(var s=db.prepareStatement("SELECT 1 FROM towns t JOIN structure_builds b ON b.town_id=t.id WHERE t.id=? AND t.civ_id=? AND t.mayor_uuid=? AND b.structure_id='s_townhall' AND b.status IN ('QUEUED','BUILDING','COMPLETE')")) {
            s.setLong(1,town);s.setLong(2,civ);s.setString(3,founder.toString());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Ратуша нового города не подготовлена.");}
        }
        try(var s=db.prepareStatement("INSERT INTO town_claims(world,chunk_x,chunk_z,town_id,civ_id,claimed_by_uuid,claimed_at) VALUES(?,?,?,?,?,?,?)")) {
            s.setString(1,world);s.setInt(2,chunkX);s.setInt(3,chunkZ);s.setLong(4,town);s.setLong(5,civ);s.setString(6,founder.toString());s.setLong(7,now);s.executeUpdate();
        }
        try(var s=db.prepareStatement("UPDATE residents SET town_id=?,updated_at=? WHERE uuid=? AND civ_id=? AND town_id=?")) {
            s.setLong(1,town);s.setLong(2,now);s.setString(3,founder.toString());s.setLong(4,civ);s.setLong(5,previousTown);if(s.executeUpdate()!=1)throw new IllegalArgumentException("Житель сменил поселение во время основания.");
        }
    }
}
