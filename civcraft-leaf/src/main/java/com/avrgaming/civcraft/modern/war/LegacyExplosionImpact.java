package com.avrgaming.civcraft.modern.war;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;

/** Original TNT sphere/order, distinct from cannon geometry and attacker permissions. */
public final class LegacyExplosionImpact {
    private final CivCraftModernPlugin plugin;
    private final Set<String> destroyable;
    private final LegacyExplosionGeometry geometry;
    public LegacyExplosionImpact(CivCraftModernPlugin plugin) {
        this.plugin=plugin;
        destroyable=SiegeCannonCatalog.destroyable(plugin.getDataFolder().toPath().resolve("civcraft/data/structures.yml").toFile());
        geometry=new LegacyExplosionGeometry(plugin,destroyable);
    }
    public void prepare(LegacyExplosionStore.Request request)throws SQLException {
        World world=Bukkit.getWorld(request.world());if(world==null)return;
        var changes=new ArrayList<LegacyExplosionStore.Hit>();
        var shape=geometry.read(request);
        plugin.storage().transaction(db->{
            if(!SiegeWorldStore.active(db,request.war(),System.currentTimeMillis()) || SiegeWorldStore.job(db,request.id())!=null || LegacyExplosionStore.damageReady(db,request.id()))return null;
            var hit=new HashSet<Long>();int radius=request.radius();
            for(int y=-radius;y<=radius;y++)for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++) {
                if((long)x*x+(long)y*y+(long)z*z>=(long)radius*radius)continue;
                int bx=Math.addExact(request.x(),x),by=Math.addExact(request.y(),y),bz=Math.addExact(request.z(),z);
                if(by<world.getMinHeight() || by>=world.getMaxHeight())continue;
                var target=target(db,request,bx,by,bz,shape);
                if(target==null || target.camp() || !target.damageable() || target.type()==null || !Set.of("COMPLETE","QUEUED","BUILDING").contains(target.status()))continue;
                if(Set.of("s_capitol","s_townhall").contains(target.type()) && ControlPointStore.point(db,request.world(),bx,by,bz)!=null)continue;
                if(!hit.add(target.build()))continue;
                var construction=target.type().startsWith("w_") && Set.of("QUEUED","BUILDING").contains(target.status())?plugin.legacyStructures().snapshotForSabotage(target.build()):null;
                changes.add(LegacyExplosionStore.damage(db,request,target,construction));
            }
            LegacyExplosionStore.damagePrepared(db,request.id());return null;
        });
        for(var change:changes) {
            if(change.stoppedConstruction())plugin.legacyStructures().stopSabotagedBuild(change.build());
            if(change.before()==change.after())continue;
            var health=plugin.storage().query(db->BuildingDamageStore.health(db,change.build()));
            Long defender=plugin.storage().query(db->{try(var s=db.prepareStatement("SELECT t.civ_id FROM structure_builds b JOIN towns t ON t.id=b.town_id WHERE b.id=?")){s.setLong(1,change.build());try(var r=s.executeQuery()){return r.next()?r.getLong(1):null;}}});
            for(var player:Bukkit.getOnlinePlayers()){var resident=plugin.storage().findResident(player.getUniqueId()).orElse(null);if(resident!=null && defender!=null && Objects.equals(defender,resident.civId()))player.sendMessage("§eВзрыв TNT: "+Objects.toString(health.name(),"Постройка")+", HP "+change.after()+"/"+health.maximum()+". Координаты: "+request.x()+", "+request.y()+", "+request.z()+".");}
        }
        var worldShape=geometry.read(request);
        plugin.storage().transaction(db->{
            if(!SiegeWorldStore.active(db,request.war(),System.currentTimeMillis()) || SiegeWorldStore.job(db,request.id())!=null)return null;
            if(!LegacyExplosionStore.damageReady(db,request.id()))throw new SQLException("Explosion damage stage is not saved");
            var cells=new ArrayList<SiegeWorldStore.Cell>();var production=SiegeWorldStore.productionLocks(db,request.world());int radius=request.radius();
            var markers=com.avrgaming.civcraft.modern.trade.TradeMarkerStore.pendingPositions(db,request.world());
            for(int y=-radius;y<=radius;y++)for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++) {
                if((long)x*x+(long)y*y+(long)z*z>=(long)radius*radius)continue;
                int bx=Math.addExact(request.x(),x),by=Math.addExact(request.y(),y),bz=Math.addExact(request.z(),z);
                if(by<world.getMinHeight() || by>=world.getMaxHeight())continue;
                var target=target(db,request,bx,by,bz,worldShape);
                if(target!=null && target.camp() || SiegeCannonImpactStore.camp(db,request.world(),bx,by,bz))continue;
                // Original damageability/control-point checks precede isDestroyed().
                if(target!=null && !Set.of("DEMOLISHED","CANCELLED").contains(target.status())) {
                    if(!target.damageable() || target.type()==null)continue;
                    if(Set.of("s_capitol","s_townhall").contains(target.type()) && ControlPointStore.point(db,request.world(),bx,by,bz)!=null)continue;
                }
                if(target!=null && !Set.of("DEMOLISHED","CANCELLED","WAR_DESTROYED").contains(target.status())) {
                    if(!Set.of("COMPLETE","QUEUED","BUILDING","WAR_DESTROY_PENDING").contains(target.status()))throw new IllegalArgumentException("Взрыв ожидает завершения восстановления постройки.");
                    if(BuildingDamageStore.health(db,target.build()).hitpoints()>0 || target.status().equals("WAR_DESTROY_PENDING"))continue;
                }
                var block=world.getBlockAt(bx,by,bz);
                if(com.avrgaming.civcraft.modern.wall.WallStore.reserved(db,request.world(),bx,by,bz))throw new IllegalArgumentException("Взрыв ожидает завершения установки или восстановления стены.");
                if(block.getState() instanceof Sign || block.getType()==Material.TNT || block.getType().isAir())continue;
                if(markers.contains(new com.avrgaming.civcraft.modern.trade.TradeMarkerStore.Position(request.world(),bx,by,bz)))throw new IllegalArgumentException("Взрыв ожидает завершения установки торгового маркера.");
                if(SiegeWorldStore.locked(db,request.world(),bx,by,bz) || com.avrgaming.civcraft.modern.road.RoadStore.reserved(db,request.world(),bx,by,bz) || production.contains(new SiegeWorldStore.Position(bx,by,bz)))throw new IllegalArgumentException("Взрыв ожидает завершения сохранения местности.");
                if(plugin.storage().isActiveStructureChunk(request.world(),bx>>4,bz>>4))throw new IllegalArgumentException("Взрыв ожидает завершения строительного журнала.");
                if(block.getState() instanceof Container)com.avrgaming.civcraft.modern.trade.GoodieChestStore.requirePositionsAvailable(db,List.of(new com.avrgaming.civcraft.modern.trade.GoodieChestStore.Position(request.world(),bx,by,bz)));
                cells.add(new SiegeWorldStore.Cell(bx,by,bz,block.getBlockData().getAsString(),BlockEntitySnapshot.read(block.getState()),"minecraft:air",null,"tnt"));
            }
            SiegeWorldStore.prepare(db,request.id(),request.war(),request.world(),"IMPACT",cells,true);
            LegacyExplosionStore.prepared(db,request.id());return null;
        });
    }
    private SiegeCannonImpactStore.Target target(Connection db,LegacyExplosionStore.Request request,int x,int y,int z,Map<LegacyExplosionGeometry.Point,LegacyExplosionGeometry.Cell> shape)throws SQLException {
        var cell=shape.get(new LegacyExplosionGeometry.Point(x,y,z));
        var stored=SiegeCannonImpactStore.target(db,request.world(),x,y,z,plugin.settings().campStructureId());
        var target=cell==null?stored:cell.target();
        // Command components are not StructureBlocks, including floor/outside-template markers.
        if(target!=null && !target.camp())try(var statement=db.prepareStatement("SELECT 1 FROM structure_chests WHERE world=? AND x=? AND y=? AND z=? UNION ALL SELECT 1 FROM structure_trade_points WHERE world=? AND x=? AND y=? AND z=? LIMIT 1")) {
            for(int offset:new int[]{0,4}){statement.setString(offset+1,request.world());statement.setInt(offset+2,x);statement.setInt(offset+3,y);statement.setInt(offset+4,z);}
            try(var rows=statement.executeQuery()){if(rows.next())target=null;}
        }
        if(target!=null && target.type()!=null && (target.type().equals("ti_road") || destroyable.contains(target.type())))return null;
        return target;
    }
}
