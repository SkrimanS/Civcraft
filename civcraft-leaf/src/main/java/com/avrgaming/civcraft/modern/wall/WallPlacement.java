package com.avrgaming.civcraft.modern.wall;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.build.ExplosionTemplateShape;
import com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection;
import com.avrgaming.civcraft.modern.road.RoadStore;
import com.avrgaming.civcraft.modern.town.*;
import com.avrgaming.civcraft.modern.trade.*;
import com.avrgaming.civcraft.modern.war.*;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;

/** Read-only validation and full before-images; payment and placement belong to the durable store. */
public final class WallPlacement {
    public record Authority(long town,long civilization) {}
    public record Cell(WallRules.Point point,String before,String nbt,String after,Long displacedBuild) {}
    public record Plan(Authority owner,String world,WallCatalog.Profile profile,WallRules.Route route,
                       boolean endpointIncluded,double cost,List<Cell> cells) {
        public Plan{cells=List.copyOf(cells);}
    }
    private record Column(int x,int z) {}
    private static final class Rejected extends IllegalArgumentException {Rejected(String message){super(message);}}
    private final CivCraftModernPlugin plugin;
    private final int minimum;
    private final Set<String> destroyable;
    public WallPlacement(CivCraftModernPlugin plugin) {
        this.plugin=plugin;
        minimum=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("civcraft/data/civ.yml").toFile()).getInt("global.min_build_height",1);
        destroyable=SiegeCannonCatalog.destroyable(plugin.getDataFolder().toPath().resolve("civcraft/data/structures.yml").toFile());
    }
    public static Authority authority(Connection db,UUID actor,WallCatalog.Profile profile,boolean building)throws SQLException {
        FoundationCreationProtection.requirePlayerAvailable(db,actor);
        if(building && WarClockStore.active(db,System.currentTimeMillis()))throw new IllegalArgumentException("Стены нельзя строить во время боевого периода.");
        try(var s=db.prepareStatement("""
            SELECT t.id,t.civ_id FROM residents r JOIN towns t ON t.id=r.town_id JOIN civilizations c ON c.id=t.civ_id
            WHERE r.uuid=? AND (t.mayor_uuid=r.uuid OR c.leader_uuid=r.uuid OR EXISTS(
              SELECT 1 FROM town_assistants a WHERE a.town_id=t.id AND a.player_uuid=r.uuid))
            """)) {
            s.setString(1,actor.toString());try(var r=s.executeQuery()) {
                if(!r.next())throw new IllegalArgumentException("Стенами управляет мэр, помощник или лидер цивилизации.");
                var owner=new Authority(r.getLong(1),r.getLong(2));
                if(FoundationCreationProtection.townHeld(db,owner.town()) || FoundationCreationProtection.civilizationHeld(db,owner.civilization()))throw new IllegalArgumentException("Сначала завершите основание города или цивилизации.");
                if(building && profile.technology()!=null && !profile.technology().isBlank())try(var tech=db.prepareStatement("SELECT 1 FROM civ_technologies WHERE civ_id=? AND tech_id=?")) {
                    tech.setLong(1,owner.civilization());tech.setString(2,profile.technology());try(var found=tech.executeQuery()){if(!found.next())throw new IllegalArgumentException("Для этой стены ещё не изучена необходимая технология.");}
                }
                return owner;
            }
        }
    }
    public Authority marker(Player player,Location at,WallCatalog.Profile profile)throws SQLException {
        return plugin.storage().query(db->{var owner=authority(db,player.getUniqueId(),profile,true);culture(db,owner,at);return owner;});
    }
    private static void culture(Connection db,Authority owner,Location at)throws SQLException {
        try(var s=db.prepareStatement("SELECT t.civ_id FROM culture_regions c JOIN towns t ON t.id=c.town_id WHERE c.world=? AND c.chunk_x=? AND c.chunk_z=?")) {
            s.setString(1,at.getWorld().getName());s.setInt(2,at.getBlockX()>>4);s.setInt(3,at.getBlockZ()>>4);
            try(var r=s.executeQuery()){if(!r.next() || r.getLong(1)!=owner.civilization())throw new IllegalArgumentException("Оба маркера стены должны находиться в культуре вашей цивилизации.");}
        }
    }
    public Plan plan(Player player,Location first,Location second,WallCatalog.Profile profile,String style)throws SQLException {
        if(!first.getWorld().equals(second.getWorld()))throw new IllegalArgumentException("Маркеры стены должны находиться в одном мире.");
        var route=WallRules.route(point(first),point(second),profile);var world=first.getWorld();
        return plugin.storage().query(db->{
            var owner=authority(db,player.getUniqueId(),profile,true);culture(db,owner,first);culture(db,owner,second);
            var production=SiegeWorldStore.productionLocks(db,world.getName());
            var markers=new HashSet<TradeMarkerStore.Position>();
            for(var job:TradeMarkerStore.jobs(db,false))if(job.world().equals(world.getName()))markers.addAll(TradeMarkerStore.positions(job));
            var columns=new HashSet<Column>();var shapes=new HashMap<Long,ExplosionTemplateShape>();var commands=new HashSet<WallRules.Point>();
            for(var cell:WallRules.blocks(route,profile,style,false))validate(db,player,world,profile,cell.position(),columns,shapes,commands,production,markers);
            boolean includeEndpoint=true;
            // Endpoint failure must not silently discard SQL/IO errors or an invalid main route.
            try {
                var endpoint=new WallRules.Route(List.of(),route.endpoint());
                for(var cell:WallRules.blocks(endpoint,profile,style,true))validate(db,player,world,profile,cell.position(),columns,shapes,commands,production,markers);
            }catch(Rejected rejection){includeEndpoint=false;}
            var cells=new ArrayList<Cell>();var positions=new ArrayList<GoodieChestStore.Position>();
            for(var cell:WallRules.blocks(route,profile,style,includeEndpoint)) {
                var p=cell.position();var block=world.getBlockAt(p.x(),p.y(),p.z());Long source=null;
                try(var s=db.prepareStatement("SELECT build_id FROM protected_blocks WHERE world=? AND x=? AND y=? AND z=?")) {
                    s.setString(1,world.getName());s.setInt(2,p.x());s.setInt(3,p.y());s.setInt(4,p.z());try(var r=s.executeQuery()){if(r.next())source=r.getLong(1);}
                }
                positions.add(new GoodieChestStore.Position(world.getName(),p.x(),p.y(),p.z()));
                cells.add(new Cell(p,block.getBlockData().getAsString(),BlockEntitySnapshot.read(block.getState()),Bukkit.createBlockData(cell.data()).getAsString(),source));
            }
            GoodieChestStore.requirePositionsAvailable(db,positions);
            return new Plan(owner,world.getName(),profile,route,includeEndpoint,WallRules.cost(route,profile,cells.size(),includeEndpoint),cells);
        });
    }
    private void validate(Connection db,Player player,World world,WallCatalog.Profile profile,WallRules.Point p,Set<Column> checked,
                          Map<Long,ExplosionTemplateShape> shapes,Set<WallRules.Point> commands,Set<SiegeWorldStore.Position> production,Set<TradeMarkerStore.Position> markers)throws SQLException {
        if(p.y()<Math.max(minimum,world.getMinHeight()) || p.y()>=Math.min(profile.maximumHeight(),world.getMaxHeight()))throw new Rejected("Стена выходит за допустимую высоту строительства.");
        if(world.getBlockAt(p.x(),p.y(),p.z()).getType()==Material.CHEST)throw new Rejected("На пути стены находится сундук.");
        if(production.contains(new SiegeWorldStore.Position(p.x(),p.y(),p.z())) || markers.contains(new TradeMarkerStore.Position(world.getName(),p.x(),p.y(),p.z()))
                || RoadStore.reserved(db,world.getName(),p.x(),p.y(),p.z()))throw new Rejected("На пути стены ещё сохраняется другая операция.");
        SiegeWorldStore.requireAreaFree(db,world.getName(),p.x(),p.y(),p.z(),1,1,1);
        var column=new Column(p.x(),p.z());
        if(!checked.contains(column)) {
            var key=new PlotStore.Key(world.getName(),p.x()>>4,p.z()>>4);
            try(var s=db.prepareStatement("SELECT 1 FROM town_claims WHERE world=? AND chunk_x=? AND chunk_z=?")) {
                s.setString(1,key.world());s.setInt(2,key.x());s.setInt(3,key.z());try(var r=s.executeQuery()){if(r.next() && !PlotStore.allowed(db,key,player.getUniqueId(),"destroy"))throw new Rejected("Нет права разрушения блоков на участке стены.");}
            }
            if(FarmPlots.registered(db,world.getName(),p.x(),p.z()).isPresent())throw new Rejected("Стена не может проходить через чанк фермы.");
            if(plugin.storage().isActiveStructureChunk(world.getName(),p.x()>>4,p.z()>>4))throw new Rejected("На пути стены ещё выполняется строительный журнал.");
            structuralColumn(db,world,p,shapes,commands);checked.add(column);
        }
        if(commands.contains(p) && world.getBlockAt(p.x(),p.y(),p.z()).getState() instanceof org.bukkit.block.Sign)throw new Rejected("Защищённая табличка здания мешает стене.");
        // Command components are excluded from StructureBlock columns, but protected at their own cell.
        for(String table:List.of("structure_chests","structure_trade_points"))try(var s=db.prepareStatement("SELECT 1 FROM "+table+" WHERE world=? AND x=? AND y=? AND z=? LIMIT 1")) {
            s.setString(1,world.getName());s.setInt(2,p.x());s.setInt(3,p.y());s.setInt(4,p.z());try(var r=s.executeQuery()){if(r.next())throw new Rejected("Защищённый компонент здания мешает стене.");}
        }
    }
    private void structuralColumn(Connection db,World world,WallRules.Point point,Map<Long,ExplosionTemplateShape> shapes,Set<WallRules.Point> commands)throws SQLException {
        try{WallStore.requireColumnsFree(db,world.getName(),point.x(),point.z());}catch(IllegalArgumentException conflict){throw new Rejected(conflict.getMessage());}
        // WarCamp registers StructureBlocks in the original, unlike an ordinary camp.
        try(var s=db.prepareStatement("""
            SELECT c.state,b.after_data,EXISTS(SELECT 1 FROM war_camp_points p
              WHERE p.camp_id=c.id AND p.kind='respawn' AND p.world=b.world
              AND p.x=b.x AND p.z=b.z AND b.y BETWEEN p.y AND p.y+1)
            FROM war_camp_blocks b JOIN war_camps c ON c.id=b.camp_id
            WHERE b.world=? AND b.x=? AND b.z=? AND c.state IN ('PREPARED','ACTIVE','RESTORING')
            """)) {
            s.setString(1,world.getName());s.setInt(2,point.x());s.setInt(3,point.z());
            try(var rows=s.executeQuery()){while(rows.next())if(!rows.getString(1).equals("ACTIVE") || rows.getBoolean(3)
                    || !Bukkit.createBlockData(rows.getString(2)).getMaterial().isAir())throw new Rejected("В колонке стены находится военный лагерь или его восстановление.");}
        }
        try(var s=db.prepareStatement("""
            SELECT DISTINCT b.id,b.structure_id,b.template_path,b.x,b.z,b.y FROM structure_builds b JOIN structure_chunks c ON c.build_id=b.id
            WHERE c.world=? AND c.chunk_x=? AND c.chunk_z=? AND b.status NOT IN ('DEMOLISHED','CANCELLED')
            """)) {
            s.setString(1,world.getName());s.setInt(2,point.x()>>4);s.setInt(3,point.z()>>4);
            try(var rows=s.executeQuery()){while(rows.next()) {
                long id=rows.getLong(1);String type=rows.getString(2);
                if(type!=null && destroyable.contains(type) || "ti_road".equals(type) || plugin.settings().campStructureId().equals(type))continue;
                if("ti_wall".equals(type) || "ti_fortifiedwall".equals(type)) {
                    try(var q=db.prepareStatement("SELECT 1 FROM protected_blocks WHERE build_id=? AND world=? AND x=? AND z=? LIMIT 1")) {
                        q.setLong(1,id);q.setString(2,world.getName());q.setInt(3,point.x());q.setInt(4,point.z());try(var found=q.executeQuery()){if(found.next())throw new Rejected("В колонке стены уже находится другая стена.");}
                    }
                    continue;
                }
                var shape=shapes.get(id);
                if(shape==null)try{shape=plugin.legacyStructures().explosionShape(rows.getString(3));shapes.put(id,shape);}catch(IOException failure){throw new SQLException("Cannot inspect wall intersection with building #"+id,failure);}
                int x=point.x()-rows.getInt(4),z=point.z()-rows.getInt(5);
                if(x<0 || x>=shape.width() || z<0 || z>=shape.depth())continue;
                for(int y=0;y<shape.height();y++)if(shape.command(x,y,z))commands.add(new WallRules.Point(point.x(),Math.addExact(rows.getInt(6),y),point.z()));
                for(int y=0;y<shape.height();y++)if(shape.solid(x,y,z))throw new Rejected("В колонке стены находится другая постройка.");
            }}
        }
    }
    public static WallRules.Point point(Location location){return new WallRules.Point(location.getBlockX(),location.getBlockY(),location.getBlockZ());}
}
