package com.avrgaming.civcraft.modern.war;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import java.io.IOException;
import java.sql.*;
import java.util.*;

/** Reconstructs bindStructureBlocks before the placement worker has completed. */
public final class LegacyExplosionGeometry {
    public record Point(int x,int y,int z) {}
    public record Cell(SiegeCannonImpactStore.Target target) {}
    private record Building(long id,String type,String status,String path,int x,int y,int z,boolean camp) {}
    private final CivCraftModernPlugin plugin;
    private final Set<String> destroyable;
    public LegacyExplosionGeometry(CivCraftModernPlugin plugin,Set<String> destroyable){this.plugin=plugin;this.destroyable=destroyable;}
    public Map<Point,Cell> read(LegacyExplosionStore.Request request)throws SQLException {
        var buildings=plugin.storage().query(db->{var out=new ArrayList<Building>();try(var s=db.prepareStatement("""
            SELECT DISTINCT b.id,b.structure_id,b.status,b.template_path,b.x,b.y,b.z,
              EXISTS(SELECT 1 FROM camps c WHERE c.world=b.world AND c.x=b.x AND c.y=b.y AND c.z=b.z)
            FROM structure_builds b JOIN structure_chunks sc ON sc.build_id=b.id WHERE sc.world=?
              AND sc.chunk_x BETWEEN ? AND ? AND sc.chunk_z BETWEEN ? AND ?
              AND b.status IN ('QUEUED','BUILDING','COMPLETE','WAR_DESTROY_PENDING','WAR_DESTROYED') ORDER BY b.id
            """)) {
            s.setString(1,request.world());s.setInt(2,(request.x()-request.radius())>>4);s.setInt(3,(request.x()+request.radius())>>4);s.setInt(4,(request.z()-request.radius())>>4);s.setInt(5,(request.z()+request.radius())>>4);
            try(var r=s.executeQuery()){while(r.next())out.add(new Building(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getInt(5),r.getInt(6),r.getInt(7),r.getBoolean(8)));}
        }return out;});
        var result=new HashMap<Point,Cell>();
        for(var building:buildings) {
            if(building.type()!=null && Set.of("ti_road","ti_wall","ti_fortifiedwall").contains(building.type()))continue;
            if(building.type()!=null && destroyable.contains(building.type()))continue;
            final com.avrgaming.civcraft.modern.build.ExplosionTemplateShape shape;
            try{shape=plugin.legacyStructures().explosionShape(building.path());}catch(IOException failure){throw new SQLException("Cannot read original explosion template for building #"+building.id(),failure);}
            boolean camp=building.camp() || plugin.settings().campStructureId().equals(building.type());
            for(int y=Math.max(building.y(),request.y()-request.radius());y<=Math.min(building.y()+shape.height()-1,request.y()+request.radius());y++)
                for(int x=Math.max(building.x(),request.x()-request.radius());x<=Math.min(building.x()+shape.width()-1,request.x()+request.radius());x++)
                    for(int z=Math.max(building.z(),request.z()-request.radius());z<=Math.min(building.z()+shape.depth()-1,request.z()+request.radius());z++) {
                        int rx=x-building.x(),ry=y-building.y(),rz=z-building.z();
                        boolean solid=shape.solid(rx,ry,rz),command=shape.command(rx,ry,rz);
                        if(camp && !solid && !command)continue; // processed camp doors may extend beyond template blocks
                        var point=new Point(x,y,z);
                        var target=solid || camp && command?new SiegeCannonImpactStore.Target(building.id(),building.type(),building.status(),camp,ry>0 && !command):null;
                        if(result.putIfAbsent(point,new Cell(target))!=null)throw new SQLException("Overlapping structural explosion templates");
                    }
            java.util.List<com.avrgaming.civcraft.modern.build.BlockBackup> previews=List.of();
            if(Set.of("QUEUED","BUILDING").contains(building.status()))previews=plugin.legacyStructures().snapshotForSabotage(building.id()).previews();
            else if(building.status().equals("WAR_DESTROY_PENDING"))previews=plugin.storage().query(db->BuildingRuinStore.sabotagePreviews(db,building.id()));
                for(var block:previews) {
                    if(!request.world().equals(block.world()) || Math.abs((long)block.x()-request.x())>request.radius() || Math.abs((long)block.y()-request.y())>request.radius() || Math.abs((long)block.z()-request.z())>request.radius())continue;
                    var point=new Point(block.x(),block.y(),block.z());var known=result.get(point);
                    if(known==null || known.target()==null)result.put(point,new Cell(new SiegeCannonImpactStore.Target(building.id(),building.type(),building.status(),camp,false)));
                }
        }
        return Map.copyOf(result);
    }
}
