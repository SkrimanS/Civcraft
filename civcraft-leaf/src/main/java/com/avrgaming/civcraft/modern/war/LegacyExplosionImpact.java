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
    public LegacyExplosionImpact(CivCraftModernPlugin plugin) {
        this.plugin=plugin;
        destroyable=SiegeCannonCatalog.destroyable(plugin.getDataFolder().toPath().resolve("civcraft/data/structures.yml").toFile());
    }
    public void prepare(LegacyExplosionStore.Request request)throws SQLException {
        World world=Bukkit.getWorld(request.world());if(world==null)return;
        var changes=new ArrayList<LegacyExplosionStore.Hit>();
        plugin.storage().transaction(db->{
            if(!SiegeWorldStore.active(db,request.war(),System.currentTimeMillis()))return null;
            if(SiegeWorldStore.job(db,request.id())!=null)return null;
            var cells=new ArrayList<SiegeWorldStore.Cell>();var hit=new HashSet<Long>();
            var production=SiegeWorldStore.productionLocks(db,request.world());int radius=request.radius();
            for(int y=-radius;y<=radius;y++)for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++) {
                if((long)x*x+(long)y*y+(long)z*z>=(long)radius*radius)continue;
                int bx=Math.addExact(request.x(),x),by=Math.addExact(request.y(),y),bz=Math.addExact(request.z(),z);
                if(by<world.getMinHeight() || by>=world.getMaxHeight())continue;
                if(SiegeWorldStore.locked(db,request.world(),bx,by,bz) || com.avrgaming.civcraft.modern.road.RoadStore.reserved(db,request.world(),bx,by,bz)
                    || production.contains(new SiegeWorldStore.Position(bx,by,bz)))throw new IllegalArgumentException("Взрыв ожидает завершения сохранения местности.");
                var target=SiegeCannonImpactStore.target(db,request.world(),bx,by,bz,plugin.settings().campStructureId());
                if(target!=null && target.type()!=null && destroyable.contains(target.type()))target=null;
                if(target!=null && target.camp() || SiegeCannonImpactStore.camp(db,request.world(),bx,by,bz))continue;
                if(target!=null && !Set.of("DEMOLISHED","CANCELLED","WAR_DESTROYED").contains(target.status())) {
                    if(!Set.of("COMPLETE","QUEUED","BUILDING","WAR_DESTROY_PENDING").contains(target.status()))throw new IllegalArgumentException("Взрыв ожидает завершения восстановления постройки.");
                    if(!target.damageable() || target.type()==null)continue;
                    boolean hall=Set.of("s_capitol","s_townhall").contains(target.type());
                    if(hall && ControlPointStore.point(db,request.world(),bx,by,bz)!=null)continue;
                    var health=BuildingDamageStore.health(db,target.build());
                    if(health.hitpoints()>0) {
                        if(hit.add(target.build())) {
                            var construction=target.type().startsWith("w_") && Set.of("QUEUED","BUILDING").contains(target.status())?plugin.legacyStructures().snapshotForSabotage(target.build()):null;
                            changes.add(LegacyExplosionStore.damage(db,request,target,construction));
                        }
                        continue;
                    }
                    if(target.status().equals("WAR_DESTROY_PENDING") || SiegeWorldStore.owned(db,request.world(),bx,by,bz)!=null)continue;
                }
                // A worksite owns its full terrain snapshot, even before protected blocks exist.
                if(plugin.storage().isActiveStructureChunk(request.world(),bx>>4,bz>>4))throw new IllegalArgumentException("Взрыв ожидает завершения строительного журнала.");
                var block=world.getBlockAt(bx,by,bz);
                if(block.getState() instanceof Sign || block.getType()==Material.TNT || block.getType().isAir())continue;
                if(block.getState() instanceof Container)com.avrgaming.civcraft.modern.trade.GoodieChestStore.requirePositionsAvailable(db,List.of(new com.avrgaming.civcraft.modern.trade.GoodieChestStore.Position(request.world(),bx,by,bz)));
                cells.add(new SiegeWorldStore.Cell(bx,by,bz,block.getBlockData().getAsString(),BlockEntitySnapshot.read(block.getState()),"minecraft:air",null,"tnt"));
            }
            SiegeWorldStore.prepare(db,request.id(),request.war(),request.world(),"IMPACT",cells,true);
            LegacyExplosionStore.prepared(db,request.id());return null;
        });
        for(var change:changes) {
            if(change.stoppedConstruction())plugin.legacyStructures().stopSabotagedBuild(change.build());
            if(change.before()==change.after())continue;
            var health=plugin.storage().query(db->BuildingDamageStore.health(db,change.build()));
            Long defender=plugin.storage().query(db->{try(var s=db.prepareStatement("SELECT t.civ_id FROM structure_builds b JOIN towns t ON t.id=b.town_id WHERE b.id=?")){s.setLong(1,change.build());try(var r=s.executeQuery()){return r.next()?r.getLong(1):null;}}});
            for(var player:Bukkit.getOnlinePlayers()){var resident=plugin.storage().findResident(player.getUniqueId()).orElse(null);if(resident!=null && defender!=null && Objects.equals(defender,resident.civId()))player.sendMessage("§eВзрыв TNT: "+Objects.toString(health.name(),"Постройка")+", HP "+change.after()+"/"+health.maximum()+". Координаты: "+request.x()+", "+request.y()+", "+request.z()+".");}
        }
    }
}
