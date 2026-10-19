package com.avrgaming.civcraft.modern.road;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.war.*;
import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;

public final class RoadPlacement {
    public record Plan(RoadRules.Route route,List<RoadStore.Cell> cells){}
    private final CivCraftModernPlugin plugin;private final StorageBootstrap storage;private final RoadCatalog catalog;private final Set<String> destroyable;private final int minimum;
    public RoadPlacement(CivCraftModernPlugin plugin,StorageBootstrap storage,RoadCatalog catalog){
        this.plugin=plugin;this.storage=storage;this.catalog=catalog;
        destroyable=SiegeCannonCatalog.destroyable(plugin.getDataFolder().toPath().resolve("civcraft/data/structures.yml").toFile());
        minimum=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("civcraft/data/civ.yml").toFile()).getInt("global.min_build_height",1);
    }
    public void marker(Player player,Location location)throws SQLException{
        storage.query(db->{long town=RoadStore.authority(db,player.getUniqueId(),true);
            try(var s=db.prepareStatement("SELECT t.civ_id,(SELECT civ_id FROM towns WHERE id=?) FROM culture_regions c JOIN towns t ON t.id=c.town_id WHERE c.world=? AND c.chunk_x=? AND c.chunk_z=?")){
                s.setLong(1,town);s.setString(2,location.getWorld().getName());s.setInt(3,Math.floorDiv(location.getBlockX(),16));s.setInt(4,Math.floorDiv(location.getBlockZ(),16));try(var r=s.executeQuery()){if(r.next() && r.getLong(1)!=r.getLong(2))throw new IllegalArgumentException("Маркер дороги нельзя поставить в культуре другой цивилизации.");}
            }return null;
        });
    }
    public Plan plan(Player player,Location first,Location second)throws SQLException{
        if(!first.getWorld().equals(second.getWorld()))throw new IllegalArgumentException("Маркеры дороги должны находиться в одном мире.");marker(player,first);marker(player,second);
        var world=first.getWorld();var route=RoadRules.route(point(first),point(second),catalog.maximum(),Math.max(minimum,world.getMinHeight()),world.getMaxHeight());
        return storage.query(db->{
            long town=RoadStore.authority(db,player.getUniqueId(),true),civ;try(var s=db.prepareStatement("SELECT civ_id FROM towns WHERE id=?")){s.setLong(1,town);try(var r=s.executeQuery()){r.next();civ=r.getLong(1);}}
            var floor=new LinkedHashSet<RoadRules.Point>();var production=SiegeWorldStore.productionLocks(db,world.getName());
            for(var base:route.floor()){
                boolean skip=false;
                try(var s=db.prepareStatement("SELECT 1 FROM structure_chunks c JOIN structure_builds b ON b.id=c.build_id WHERE c.world=? AND c.chunk_x=? AND c.chunk_z=? AND b.structure_id=? AND b.status NOT IN ('DEMOLISHED','CANCELLED') LIMIT 1")){
                    s.setString(1,world.getName());s.setInt(2,Math.floorDiv(base.x(),16));s.setInt(3,Math.floorDiv(base.z(),16));s.setString(4,plugin.settings().campStructureId());try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Дорога не может проходить через чанк лагеря.");}
                }
                SiegeWorldStore.requireAreaFree(db,world.getName(),base.x(),base.y(),base.z(),1,4,1);
                for(int dy=0;dy<4;dy++){
                    int y=base.y()+dy;var block=world.getBlockAt(base.x(),y,base.z());if(block.getState() instanceof Chest)throw new IllegalArgumentException("На пути дороги находится сундук.");
                    if(production.contains(new SiegeWorldStore.Position(base.x(),y,base.z())))throw new IllegalArgumentException("На пути дороги ещё сохраняется рост или посадка растений.");
                    var other=RoadStore.at(db,world.getName(),base.x(),y,base.z());if(other!=null){if(other.road().civ()!=civ)throw new IllegalArgumentException("На пути находится дорога другой цивилизации.");if(!other.road().state().equals("ACTIVE"))throw new IllegalArgumentException("Другая дорога ещё сохраняется.");skip=true;}
                    try(var s=db.prepareStatement("SELECT b.structure_id,t.civ_id FROM protected_blocks p JOIN structure_builds b ON b.id=p.build_id LEFT JOIN towns t ON t.id=b.town_id WHERE p.world=? AND p.x=? AND p.y=? AND p.z=?")){
                        s.setString(1,world.getName());s.setInt(2,base.x());s.setInt(3,y);s.setInt(4,base.z());try(var r=s.executeQuery()){if(r.next()){
                            String type=r.getString(1);if(type==null || !destroyable.contains(type)){if(r.getObject(2)==null || r.getLong(2)!=civ)throw new IllegalArgumentException("Защищённое или чужое здание мешает дороге.");skip=true;}
                        }}
                    }
                }
                if(!skip)floor.add(base);
            }
            if(floor.isEmpty())throw new IllegalArgumentException("Весь маршрут занят существующими постройками или дорогами.");
            var positions=new LinkedHashSet<RoadRules.Point>();for(var base:floor)for(int dy=0;dy<4;dy++)positions.add(new RoadRules.Point(base.x(),base.y()+dy,base.z()));
            var cells=new ArrayList<RoadStore.Cell>();for(var p:positions){var b=world.getBlockAt(p.x(),p.y(),p.z());Long source=null;
                try(var s=db.prepareStatement("SELECT build_id FROM protected_blocks WHERE world=? AND x=? AND y=? AND z=?")){s.setString(1,world.getName());s.setInt(2,p.x());s.setInt(3,p.y());s.setInt(4,p.z());try(var r=s.executeQuery()){if(r.next())source=r.getLong(1);}}
                cells.add(new RoadStore.Cell(p,b.getBlockData().getAsString(),BlockEntitySnapshot.read(b.getState()),floor.contains(p)?"minecraft:cobblestone":"minecraft:air",floor.contains(p),source));
            }return new Plan(route,List.copyOf(cells));
        });
    }
    public static RoadRules.Point point(Location location){return new RoadRules.Point(location.getBlockX(),location.getBlockY(),location.getBlockZ());}
}
