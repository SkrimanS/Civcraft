package com.avrgaming.civcraft.modern.war;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Sign;
import org.bukkit.block.TileState;
import org.bukkit.block.Container;
import net.kyori.adventure.text.Component;

/** Plan the original x/z/y blast order and all persistent health changes in one transaction. */
public final class SiegeCannonImpact {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final SiegeCannonCatalog catalog;
    private final Set<String> destroyable;
    public SiegeCannonImpact(CivCraftModernPlugin plugin,StorageBootstrap storage,SiegeCannonCatalog catalog){this.plugin=plugin;this.storage=storage;this.catalog=catalog;destroyable=SiegeCannonCatalog.destroyable(plugin.getDataFolder().toPath().resolve("civcraft/data/structures.yml").toFile());}
    public void prepare(SiegeCannonStore.Shot shot,SiegeCannonStore.Cannon cannon,World world)throws SQLException{
        if(storage.query(db->SiegeWorldStore.job(db,shot.id()))!=null)return;
        var center=new Location(world,shot.position().x(),shot.position().y(),shot.position().z());
        record Notice(long build,String message){}var notices=new ArrayList<Notice>();
        storage.transaction(db->{
            if(!SiegeWorldStore.active(db,cannon.draft().war(),System.currentTimeMillis()))return null;
            var cells=new ArrayList<SiegeWorldStore.Cell>();var hit=new HashSet<Long>();boolean interrupted=false;int radius=(int)catalog.radius();
            var production=SiegeWorldStore.productionLocks(db,world.getName());
            blast:for(int x=-radius;x<radius;x++)for(int z=-radius;z<radius;z++)for(int y=-radius;y<radius;y++){
                int by=center.getBlockY()+y;if(by<world.getMinHeight() || by>=world.getMaxHeight())continue;
                var b=world.getBlockAt(center.getBlockX()+x,by,center.getBlockZ()+z);
                if(b.getType()==Material.BEDROCK || b.getLocation().distanceSquared(center)>catalog.radius()*catalog.radius())continue;
                if(SiegeWorldStore.locked(db,world.getName(),b.getX(),b.getY(),b.getZ()))throw new IllegalArgumentException("В зоне попадания ещё сохраняются изменения мира.");
                if(com.avrgaming.civcraft.modern.road.RoadStore.reserved(db,world.getName(),b.getX(),b.getY(),b.getZ()))throw new IllegalArgumentException("В зоне попадания ещё сохраняется дорога.");
                if(production.contains(new SiegeWorldStore.Position(b.getX(),b.getY(),b.getZ())))throw new IllegalArgumentException("В зоне попадания ещё сохраняется рост или посадка растений.");
                var target=SiegeCannonImpactStore.target(db,world.getName(),b.getX(),b.getY(),b.getZ(),plugin.settings().campStructureId());
                if(target!=null && target.type()!=null && destroyable.contains(target.type()))target=null;
                if(target!=null && target.camp() || SiegeCannonImpactStore.camp(db,world.getName(),b.getX(),b.getY(),b.getZ()))continue;
                if(target!=null && !Set.of("DEMOLISHED","CANCELLED","WAR_DESTROYED").contains(target.status())){
                    if(!target.damageable() || b.getType().isAir() || b.getState() instanceof Sign)continue;
                    if(target.type()==null)continue;
                    boolean hall=Set.of("s_capitol","s_townhall").contains(target.type());
                    if(hall && ControlPointStore.point(db,world.getName(),b.getX(),b.getY(),b.getZ())!=null)continue;
                    var health=BuildingDamageStore.health(db,target.build());
                    if(health.hitpoints()>0){
                        if(!hit.add(target.build()) || catalog.structureDamage()==0)continue;
                        try{
                            int after;
                            if(hall)after=SiegeCannonImpactStore.hall(db,shot.id()+":"+target.build(),target.build(),catalog.structureDamage(),catalog.controlDamage(),cannon.draft().war());
                            else after=BuildingDamageStore.cannonHit(db,shot.id()+":"+target.build(),shot.actor(),world.getName(),b.getX(),b.getY(),b.getZ(),catalog.structureDamage(),System.currentTimeMillis()).hitpoints();
                            if(after>=0)notices.add(new Notice(target.build(),health.name()+": попадание осадной пушки, "+after+"/"+health.maximum()+" HP. Координаты: "+b.getX()+", "+b.getY()+", "+b.getZ()+"."));
                        }catch(IllegalArgumentException denied){
                            var actor=Bukkit.getPlayer(shot.actor());if(actor!=null){notices.add(new Notice(-1,denied.getMessage()));interrupted=true;break blast;}
                        }
                        continue;
                    }
                    if(target.status().equals("WAR_DESTROY_PENDING") || SiegeWorldStore.owned(db,world.getName(),b.getX(),b.getY(),b.getZ())!=null)continue;
                }
                // WarRegen.explodeThisBlock preserves signs and TNT, and retains container contents for restoration.
                if(b.getState() instanceof Sign || b.getType()==Material.TNT || b.getType().isAir())continue;
                if(b.getState() instanceof Container)com.avrgaming.civcraft.modern.trade.GoodieChestStore.requirePositionsAvailable(db,List.of(new com.avrgaming.civcraft.modern.trade.GoodieChestStore.Position(world.getName(),b.getX(),b.getY(),b.getZ())));
                cells.add(new SiegeWorldStore.Cell(b.getX(),b.getY(),b.getZ(),b.getBlockData().getAsString(),BlockEntitySnapshot.read(b.getState()),"minecraft:air",null,"blast"));
            }
            SiegeWorldStore.prepare(db,shot.id(),cannon.draft().war(),world.getName(),"IMPACT",cells,true);
            if(!interrupted)try(var s=db.prepareStatement("INSERT OR IGNORE INTO siege_player_hits(id,player_uuid,damage) VALUES(?,?,?)")){
                for(var player:world.getPlayers())if(player.getLocation().distanceSquared(center)<=catalog.radius()*catalog.radius()){
                    s.setString(1,shot.id());s.setString(2,player.getUniqueId().toString());s.setDouble(3,catalog.playerDamage());s.addBatch();
                }s.executeBatch();
            }
            return null;
        });
        var attacker=storage.findResident(shot.actor()).orElse(null);
        for(var notice:notices){
            Long defender=notice.build()<0?null:storage.query(db->{try(var s=db.prepareStatement("SELECT t.civ_id FROM structure_builds b JOIN towns t ON t.id=b.town_id WHERE b.id=?")){s.setLong(1,notice.build());try(var r=s.executeQuery()){return r.next()?r.getLong(1):null;}}});
            for(var player:Bukkit.getOnlinePlayers()){
                var resident=storage.findResident(player.getUniqueId()).orElse(null);
                if(player.getUniqueId().equals(shot.actor()) || notice.build()>=0 && resident!=null && resident.civId()!=null && (Objects.equals(resident.civId(),defender) || attacker!=null && Objects.equals(resident.civId(),attacker.civId())))player.sendMessage(Component.text(notice.message()));
            }
        }
    }
}
