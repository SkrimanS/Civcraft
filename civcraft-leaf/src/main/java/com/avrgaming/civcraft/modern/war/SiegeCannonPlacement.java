package com.avrgaming.civcraft.modern.war;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.build.*;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;

/** Validate the source volume and collect complete images before any item/world mutation. */
public final class SiegeCannonPlacement {
    public record Plan(SiegeCannonStore.Draft draft,List<SiegeWorldStore.Cell> cells) {}
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final SiegeCannonCatalog catalog;
    private final double spawnDistance;
    private final Set<String> destroyable;
    public SiegeCannonPlacement(CivCraftModernPlugin plugin,StorageBootstrap storage,SiegeCannonCatalog catalog){
        this.plugin=plugin;this.storage=storage;this.catalog=catalog;
        destroyable=SiegeCannonCatalog.destroyable(plugin.getDataFolder().toPath().resolve("civcraft/data/structures.yml").toFile());
        var config=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("civcraft/data/civ.yml").toFile());spawnDistance=config.getDouble("global.distance_from_spawn",1000);
    }
    public Plan plan(Player player)throws java.io.IOException,SQLException{
        var location=player.getLocation();var world=location.getWorld();String direction=WarCampPlacementRules.direction(location.getYaw());
        var template=SiegeCannonTemplate.load(plugin.getDataFolder().toPath().resolve("legacy-templates/themes/default/structures/"+catalog.template()+"/"+catalog.template()+"_"+direction+".def"));
        var origin=SiegeCannonRules.origin(location.getX(),location.getY(),location.getZ(),location.getYaw(),template.width(),template.depth());
        SiegeCannonRules.height(location.getY(),origin.y(),template.height(),world.getMinHeight(),world.getMaxHeight());
        long war=storage.query(db->{long current=SiegeCannonStore.currentWar(db,System.currentTimeMillis());AdminCivilizationStore.checkDistance(db,world.getName(),origin.x(),origin.y(),origin.z(),spawnDistance,player.isOp());return current;});
        double total=0;for(int x=0;x<template.width();x++)for(int z=0;z<template.depth();z++)total+=world.getHighestBlockYAt(origin.x()+x,origin.z()+z);
        SiegeCannonRules.terrain(origin.y(),total/(template.width()*(double)template.depth()));
        // Check the whole volume, including the places where the template writes air.
        storage.query(db->{
            com.avrgaming.civcraft.modern.road.RoadStore.requireAreaFree(db,world.getName(),origin.x(),origin.y(),origin.z(),template.width(),template.height(),template.depth(),null);
            var production=SiegeWorldStore.productionLocks(db,world.getName());
            for(int x=0;x<template.width();x++)for(int y=0;y<template.height();y++)for(int z=0;z<template.depth();z++){
                var b=world.getBlockAt(origin.x()+x,origin.y()+y,origin.z()+z);
                if(b.getState() instanceof Chest)throw new IllegalArgumentException("Сундук мешает размещению пушки.");
                var target=SiegeCannonImpactStore.target(db,world.getName(),b.getX(),b.getY(),b.getZ(),plugin.settings().campStructureId());
                boolean protectedBlock=storage.isProtectedBlock(world.getName(),b.getX(),b.getY(),b.getZ()) && (target==null || target.type()==null || !destroyable.contains(target.type()));
                if(protectedBlock || SiegeWorldStore.owned(db,world.getName(),b.getX(),b.getY(),b.getZ())!=null || SiegeWorldStore.locked(db,world.getName(),b.getX(),b.getY(),b.getZ()))throw new IllegalArgumentException("Защищённый объект или другая пушка мешает размещению.");
                if(production.contains(new SiegeWorldStore.Position(b.getX(),b.getY(),b.getZ())))throw new IllegalArgumentException("На месте пушки ещё сохраняется рост или посадка растений.");
                try(var s=db.prepareStatement("SELECT 1 FROM war_camp_blocks b JOIN war_camps c ON c.id=b.camp_id WHERE b.world=? AND b.x=? AND b.y=? AND b.z=? AND c.state<>'RESTORED' LIMIT 1")){
                    s.setString(1,world.getName());s.setInt(2,b.getX());s.setInt(3,b.getY());s.setInt(4,b.getZ());try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Военный лагерь мешает размещению пушки.");}
                }
            }return null;
        });
        var cells=new ArrayList<SiegeWorldStore.Cell>();
        for(var block:template.blocks()){
            var b=world.getBlockAt(origin.x()+block.x(),origin.y()+block.y(),origin.z()+block.z());
            String before=b.getBlockData().getAsString();var after=LegacyBlockDataFactory.create(block);
            // Original cannon keeps existing block data when the numeric material already matches.
            String data=b.getType()==after.getMaterial()?before:after.getAsString();
            cells.add(new SiegeWorldStore.Cell(b.getX(),b.getY(),b.getZ(),before,BlockEntitySnapshot.read(b.getState()),data,b.getType()==after.getMaterial()?BlockEntitySnapshot.read(b.getState()):null,after.getMaterial().isAir()?"air":"block"));
        }
        for(var entry:template.markers().entrySet()){
            if(entry.getKey().equals("cannon"))continue;
            var marker=entry.getValue();var b=world.getBlockAt(origin.x()+marker.x(),origin.y()+marker.y(),origin.z()+marker.z());
            cells.add(new SiegeWorldStore.Cell(b.getX(),b.getY(),b.getZ(),b.getBlockData().getAsString(),BlockEntitySnapshot.read(b.getState()),LegacyBlockDataFactory.create(marker).getAsString(),null,entry.getKey()));
        }
        var draft=new SiegeCannonStore.Draft(UUID.randomUUID().toString(),player.getUniqueId(),war,world.getName(),origin,template.muzzle(origin),Byte.toUnsignedInt(template.markers().get("cannon").legacyData()));
        return new Plan(draft,List.copyOf(cells));
    }
}
