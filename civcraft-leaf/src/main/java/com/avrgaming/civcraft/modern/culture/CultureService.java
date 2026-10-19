package com.avrgaming.civcraft.modern.culture;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.io.File;
import java.sql.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

public final class CultureService {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final CultureLevels levels;
    private final Set<CultureRegions.Cell> pending=new HashSet<>();
    private List<CultureRegions.Town> lastTowns=List.of();
    private Map<CultureRegions.Cell,Long> lastPlots=Map.of();
    private boolean initialized;
    private boolean refreshNeeded;
    public CultureService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;
        levels=new CultureLevels(YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/culture.yml")).getMapList("culture_levels"));
        double base=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/town.yml")).getDouble("town.base_hammer_rate",1);
        if(!Double.isFinite(base) || base<=0)throw new IllegalArgumentException("Неверная базовая скорость производства молотков");
        storage.setLegacyTownBaseHammers(base);
        storage.transaction(db->{
            boolean found=false;try(var s=db.createStatement();var r=s.executeQuery("PRAGMA table_info(towns)")){while(r.next())if(r.getString("name").equals("accumulated_culture"))found=true;}
            try(var s=db.createStatement()){if(!found)s.executeUpdate("ALTER TABLE towns ADD COLUMN accumulated_culture INTEGER NOT NULL DEFAULT 0");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS culture_regions(world TEXT NOT NULL,chunk_x INTEGER NOT NULL,chunk_z INTEGER NOT NULL,town_id INTEGER NOT NULL,PRIMARY KEY(world,chunk_x,chunk_z),FOREIGN KEY(town_id) REFERENCES towns(id) ON DELETE CASCADE)");
                s.executeUpdate("CREATE INDEX IF NOT EXISTS culture_region_town ON culture_regions(town_id)");}
            try(var marker=db.prepareStatement("INSERT OR IGNORE INTO civcraft_meta(key,value) VALUES('culture_base_hammers_v1',?)")) {
                marker.setString(1,Double.toString(base));
                if(marker.executeUpdate()==1)try(var update=db.prepareStatement("UPDATE towns SET hammers_per_hour=? WHERE hammers_per_hour=100")){update.setDouble(1,base);update.executeUpdate();}
            }
            return null;
        });
    }
    public void tick() {
        try {
            var towns=storage.query(db->{var list=new ArrayList<CultureRegions.Town>();try(var s=db.createStatement();var r=s.executeQuery("SELECT id,world,x,z,accumulated_culture FROM towns WHERE "+com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.visible(db,"towns","towns.id")+" ORDER BY id")) {
                while(r.next()){long value=r.getLong(5),id=r.getLong(1);int x=Math.floorDiv(r.getInt(3),16),z=Math.floorDiv(r.getInt(4),16);
                    try(var hall=db.prepareStatement("""
                        SELECT MIN(sc.chunk_x)+(MAX(sc.chunk_x)-MIN(sc.chunk_x)+1)/2,
                               MIN(sc.chunk_z)+(MAX(sc.chunk_z)-MIN(sc.chunk_z)+1)/2
                        FROM structure_builds sb JOIN structure_chunks sc ON sc.build_id=sb.id
                        WHERE sb.town_id=? AND sb.structure_id IN ('s_capitol','s_townhall') AND sb.status IN ('QUEUED','BUILDING','COMPLETE')
                        GROUP BY sb.id ORDER BY sb.id LIMIT 1
                        """)){hall.setLong(1,id);try(var center=hall.executeQuery()){if(center.next()){x=center.getInt(1);z=center.getInt(2);}}}
                    list.add(new CultureRegions.Town(id,new CultureRegions.Cell(r.getString(2),x,z),value,levels.forCulture(value)));}}
                return List.copyOf(list);});
            if(!initialized || !towns.equals(lastTowns))storage.transaction(db->{com.avrgaming.civcraft.modern.town.TownLimitsStore.seed(db,System.currentTimeMillis());return null;});
            var plots=storage.query(db->{var map=new HashMap<CultureRegions.Cell,Long>();try(var s=db.createStatement();var r=s.executeQuery("SELECT world,chunk_x,chunk_z,town_id FROM town_claims")){while(r.next())map.put(new CultureRegions.Cell(r.getString(1),r.getInt(2),r.getInt(3)),r.getLong(4));}return Map.copyOf(map);});
            if(!initialized || !towns.equals(lastTowns) || !plots.equals(lastPlots)) {
                storage.transaction(db->{
                    var previous=new HashMap<CultureRegions.Cell,Long>();try(var s=db.createStatement();var r=s.executeQuery("SELECT world,chunk_x,chunk_z,town_id FROM culture_regions")){while(r.next())previous.put(new CultureRegions.Cell(r.getString(1),r.getInt(2),r.getInt(3)),r.getLong(4));}
                    var result=CultureRegions.calculate(towns,plots,previous);
                    try(var s=db.createStatement()){s.executeUpdate("DELETE FROM culture_regions");}
                    try(var s=db.prepareStatement("INSERT INTO culture_regions VALUES(?,?,?,?)")){for(var entry:result.entrySet()){var c=entry.getKey();s.setString(1,c.world());s.setInt(2,c.x());s.setInt(3,c.z());s.setLong(4,entry.getValue());s.addBatch();}s.executeBatch();}
                    return null;
                });
                initialized=true;lastTowns=towns;lastPlots=plots;
                refreshNeeded=true;
            }
            warmBiomes();
            if(refreshNeeded && pending.isEmpty() && storage.query(db->{try(var s=db.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM culture_regions cr LEFT JOIN culture_biome_cache b ON b.world=cr.world AND b.chunk_x=cr.chunk_x AND b.chunk_z=cr.chunk_z WHERE b.biome IS NULL")){r.next();return r.getInt(1)==0;}})) {
                var civs=storage.query(db->{var ids=new HashSet<Long>();try(var s=db.createStatement();var r=s.executeQuery("SELECT DISTINCT t.civ_id FROM towns t JOIN structure_builds sb ON sb.town_id=t.id WHERE sb.status='QUEUED'")){while(r.next())ids.add(r.getLong(1));}return ids;});
                for(long civ:civs)plugin.governments().refreshProduction(civ);
                refreshNeeded=false;
            }
        }catch(SQLException failure){plugin.getLogger().warning("Culture territory calculation failed: "+failure.getMessage());}
    }
    private void warmBiomes()throws SQLException {
        if(pending.size()>=4)return;
        var cells=storage.query(db->{var list=new ArrayList<CultureRegions.Cell>();try(var s=db.createStatement();var r=s.executeQuery("SELECT cr.world,cr.chunk_x,cr.chunk_z FROM culture_regions cr LEFT JOIN culture_biome_cache b ON b.world=cr.world AND b.chunk_x=cr.chunk_x AND b.chunk_z=cr.chunk_z WHERE b.biome IS NULL LIMIT 16")){while(r.next())list.add(new CultureRegions.Cell(r.getString(1),r.getInt(2),r.getInt(3)));}return list;});
        for(var cell:cells) {
            if(pending.size()>=4)break;
            var world=plugin.getServer().getWorld(cell.world());if(world==null || !pending.add(cell))continue;
            world.getChunkAtAsync(cell.x(),cell.z(),true).whenComplete((chunk,failure)->{
                if(!plugin.isEnabled())return;
                plugin.getServer().getScheduler().runTask(plugin,()->{
                    pending.remove(cell);
                    if(failure!=null){plugin.getLogger().warning("Unable to sample cultural biome: "+failure.getMessage());return;}
                    try{plugin.biomeSurvey().sample(new Location(world,cell.x()*16,64,cell.z()*16));refreshNeeded=true;}catch(SQLException error){plugin.getLogger().warning("Unable to save cultural biome: "+error.getMessage());}
                });
            });
        }
    }
    public void info(Player player)throws SQLException {
        var resident=storage.findResident(player.getUniqueId()).orElseThrow();if(resident.townId()==null)throw new IllegalArgumentException("Вы не состоите в городе.");
        String info=storage.query(db->{
            long culture;try(var s=db.prepareStatement("SELECT accumulated_culture FROM towns WHERE id=?")){s.setLong(1,resident.townId());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing culture town");culture=r.getLong(1);}}
            int total,sampled;try(var s=db.prepareStatement("SELECT COUNT(*),COUNT(b.biome) FROM culture_regions cr LEFT JOIN culture_biome_cache b ON b.world=cr.world AND b.chunk_x=cr.chunk_x AND b.chunk_z=cr.chunk_z WHERE cr.town_id=?")){s.setLong(1,resident.townId());try(var r=s.executeQuery()){r.next();total=r.getInt(1);sampled=r.getInt(2);}}
            var level=levels.forCulture(culture);return "Культура: "+culture+". Уровень: "+level.number()+". Радиус: "+level.radius()+". Чанков территории: "+total+". Потенциал изучен: "+sampled+"/"+total+".";
        });player.sendMessage(Component.text(info));
    }
}
