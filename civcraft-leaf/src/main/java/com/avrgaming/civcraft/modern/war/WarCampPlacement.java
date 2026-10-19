package com.avrgaming.civcraft.modern.war;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.build.*;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.town.PlotStore;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;

/** Build a complete before/after journal without changing terrain or consuming the item. */
public final class WarCampPlacement {
    private final CivCraftModernPlugin plugin;private final StorageBootstrap storage;private final String template;private final int minimum;private final boolean foundationEnabled;private final double administrativeDistance;
    public record Marker(String kind,int x,int y,int z) {}
    public record Plan(WarCampStore.Authority authority,WarCampPlacementRules.Origin origin,List<WarCampStore.Block> blocks,List<Marker> markers,List<FoundationSnapshotValidator.Column> foundation) {}
    private record Position(int x,int y,int z) {}
    public WarCampPlacement(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;
        var config=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("civcraft/data/war.yml").toFile());template=config.getString("warcamp.template","warcamp");
        if(!template.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("Неверное имя шаблона военного лагеря");
        var civ=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("civcraft/data/civ.yml").toFile());minimum=civ.getInt("global.min_build_height",1);
        foundationEnabled=Boolean.parseBoolean(civ.getString("global.structure_validation","true"));
        administrativeDistance=civ.getDouble("global.distance_from_spawn",1000);
        storage.transaction(db->{WarCampStore.install(db);AdminCivilizationStore.install(db);return null;});
    }
    public Plan plan(Player player)throws java.io.IOException,SQLException {
        long now=System.currentTimeMillis();var authority=storage.query(db->WarCampStore.authority(db,player.getUniqueId(),now));
        var location=player.getLocation();var world=location.getWorld();String direction=WarCampPlacementRules.direction(location.getYaw());
        Path file=plugin.getDataFolder().toPath().resolve("legacy-templates/themes/default/structures/"+template+"/"+template+"_"+direction+".def");
        var definition=new LegacyDefTemplateLoader().load(file);var origin=WarCampPlacementRules.origin(location.getX(),location.getY(),location.getZ(),location.getYaw(),definition.sizeX(),definition.sizeZ());
        WarCampPlacementRules.height(location.getY(),origin.y(),definition.sizeY(),minimum,world.getMaxHeight());
        storage.query(db->{AdminCivilizationStore.checkDistance(db,world.getName(),origin.x(),origin.y(),origin.z(),administrativeDistance,player.isOp());return null;});
        verifyRegion(storage,player.getUniqueId(),world.getName(),origin,definition.sizeX(),definition.sizeY(),definition.sizeZ());
        double total=0;for(int x=0;x<definition.sizeX();x++)for(int z=0;z<definition.sizeZ();z++)total+=world.getHighestBlockYAt(origin.x()+x,origin.z()+z);
        WarCampPlacementRules.terrain(origin.y(),total/(definition.sizeX()*(double)definition.sizeZ()));
        var after=new LinkedHashMap<Position,String>();var markers=new ArrayList<Marker>();
        var before=new HashMap<Position,String>();var nbt=new HashMap<Position,String>();
        for(var block:definition.blocks()) {
            var position=new Position(origin.x()+block.x(),origin.y()+block.y(),origin.z()+block.z());var current=world.getBlockAt(position.x(),position.y(),position.z());
            if(current.getType()==Material.CHEST)throw new IllegalArgumentException("Сундук мешает размещению военного лагеря: "+position.x()+","+position.y()+","+position.z()+".");
            String saved=current.getBlockData().getAsString();before.put(position,saved);nbt.put(position,BlockEntitySnapshot.read(current.getState()));
            // WarCamp skips command/literal signs while placing ordinary template blocks.
            if(!block.signLines().isEmpty()){after.put(position,saved);String command=block.signLines().getFirst().trim().toLowerCase(Locale.ROOT);if(Set.of("/respawn","/control").contains(command))markers.add(new Marker(command.substring(1),position.x(),position.y(),position.z()));}
            else after.put(position,LegacyBlockDataFactory.create(block).getAsString());
        }
        for(var marker:markers) {
            var base=new Position(marker.x(),marker.y(),marker.z());var top=new Position(marker.x(),marker.y()+1,marker.z());
            if(!after.containsKey(top))throw new IllegalArgumentException("Разметка лагеря выходит за объём шаблона.");
            after.put(base,marker.kind().equals("control")?"minecraft:oak_fence":"minecraft:air");after.put(top,marker.kind().equals("control")?"minecraft:obsidian":"minecraft:air");
        }
        if(markers.stream().noneMatch(m->m.kind().equals("control"))||markers.stream().noneMatch(m->m.kind().equals("respawn")))throw new IllegalArgumentException("В шаблоне лагеря нет необходимых контрольных/респаун-точек.");
        var blocks=new ArrayList<WarCampStore.Block>();after.forEach((p,data)->blocks.add(new WarCampStore.Block(world.getName(),p.x(),p.y(),p.z(),before.get(p),data,nbt.get(p))));
        var foundation=definition.blocks().stream().filter(block->block.y()==0&&block.legacyId()!=0).map(block->new FoundationSnapshotValidator.Column(origin.x()+block.x(),origin.z()+block.z())).toList();
        return new Plan(authority,origin,List.copyOf(blocks),List.copyOf(markers),foundation);
    }
    public boolean foundationEnabled(){return foundationEnabled;}
    public static void verifyRegion(StorageBootstrap storage,UUID actor,String world,WarCampPlacementRules.Origin origin,int width,int height,int depth)throws SQLException {
        storage.query(db->{verifyRegion(db,actor,world,origin,width,height,depth);return null;});
    }
    public static void verifyRegion(Connection db,UUID actor,String world,WarCampPlacementRules.Origin origin,int width,int height,int depth)throws SQLException {
        SiegeWorldStore.requireAreaFree(db,world,origin.x(),origin.y(),origin.z(),width,height,depth);
        com.avrgaming.civcraft.modern.road.RoadStore.requireAreaFree(db,world,origin.x(),origin.y(),origin.z(),width,height,depth,null);
        int maxX=Math.addExact(origin.x(),width-1),maxY=Math.addExact(origin.y(),height-1),maxZ=Math.addExact(origin.z(),depth-1);
        try(var s=db.prepareStatement("SELECT 1 FROM protected_blocks p JOIN structure_builds b ON b.id=p.build_id WHERE p.world=? AND p.x BETWEEN ? AND ? AND p.y BETWEEN ? AND ? AND p.z BETWEEN ? AND ? AND b.status NOT IN ('DEMOLISHED','CANCELLED','WAR_DESTROYED') LIMIT 1")) {
            s.setString(1,world);s.setInt(2,origin.x());s.setInt(3,maxX);s.setInt(4,origin.y());s.setInt(5,maxY);s.setInt(6,origin.z());s.setInt(7,maxZ);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Защищённое здание мешает военному лагерю.");}
        }
        int minCX=Math.floorDiv(origin.x(),16),maxCX=Math.floorDiv(maxX,16),minCZ=Math.floorDiv(origin.z(),16),maxCZ=Math.floorDiv(maxZ,16);
        try(var s=db.prepareStatement("SELECT 1 FROM structure_chunks sc JOIN structure_builds b ON b.id=sc.build_id WHERE sc.world=? AND sc.chunk_x BETWEEN ? AND ? AND sc.chunk_z BETWEEN ? AND ? AND b.structure_id IN ('ti_farm','ti_wall','ti_fortifiedwall') AND b.status NOT IN ('DEMOLISHED','CANCELLED','WAR_DESTROYED') LIMIT 1")) {
            s.setString(1,world);s.setInt(2,minCX);s.setInt(3,maxCX);s.setInt(4,minCZ);s.setInt(5,maxCZ);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Ферма или стена мешает военному лагерю.");}
        }
        try(var s=db.prepareStatement("SELECT chunk_x,chunk_z FROM town_claims WHERE world=? AND chunk_x BETWEEN ? AND ? AND chunk_z BETWEEN ? AND ?")) {
            s.setString(1,world);s.setInt(2,minCX);s.setInt(3,maxCX);s.setInt(4,minCZ);s.setInt(5,maxCZ);
            try(var r=s.executeQuery()){while(r.next())if(!PlotStore.allowed(db,new PlotStore.Key(world,r.getInt(1),r.getInt(2)),actor,"destroy"))throw new IllegalArgumentException("Нет права разрушения на участке под военным лагерем.");}
        }
    }
}
