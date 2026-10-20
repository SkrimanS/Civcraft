package com.avrgaming.civcraft.modern.wall;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.war.BlockEntitySnapshot;
import java.sql.SQLException;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;

/** Idempotent after-image worker; wire only with wall operation protection and command routing. */
public final class WallBuildService {
    private final CivCraftModernPlugin plugin;
    private final WallCatalog catalog;
    private final WallPlacement placement;
    private WallStore.Job working;
    private List<WallStore.Image> images;
    private int index;
    private long lastWarning;
    public WallBuildService(CivCraftModernPlugin plugin,WallCatalog catalog)throws SQLException {
        this.plugin=plugin;this.catalog=catalog;placement=new WallPlacement(plugin);
        plugin.storage().transaction(db->{WallStore.install(db);return null;});
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,1L,1L);
    }
    /** The marker session keeps request stable through retries, including an uncertain SQL response. */
    public long build(String request,Player actor,Location first,Location second,String type,String style)throws SQLException {
        var existing=plugin.storage().query(db->WallStore.request(db,request,actor.getUniqueId()));if(existing!=null)return existing;
        var plan=placement.plan(actor,first,second,catalog.profile(type),style);
        var world=Bukkit.getWorld(plan.world());if(world==null)throw new IllegalArgumentException("Мир стены недоступен.");
        for(var cell:plan.cells()) {
            var p=cell.point();var block=world.getBlockAt(p.x(),p.y(),p.z());
            if(!block.getBlockData().getAsString().equals(cell.before()) || !Objects.equals(BlockEntitySnapshot.read(block.getState()),cell.nbt()))
                throw new IllegalArgumentException("Блоки на маршруте изменились. Повторите размещение стены.");
        }
        return plugin.storage().transaction(db->WallStore.prepare(db,request,actor.getUniqueId(),plan,System.currentTimeMillis()));
    }
    private void tick() {
        try {
            if(working!=null && !plugin.storage().query(db->WallStore.pending(db,working)))reset();
            if(working!=null && Bukkit.getWorld(working.world())==null)reset();
            if(working==null)for(var job:plugin.storage().query(WallStore::pending)) {
                if(!job.kind().equals("BUILD") || Bukkit.getWorld(job.world())==null)continue;
                working=job;images=plugin.storage().query(db->WallStore.images(db,job.id()));index=0;break;
            }
            if(working==null)return;var world=Bukkit.getWorld(working.world());if(world==null)return;
            for(int count=0;count<256 && index<images.size();count++,index++) {
                var image=images.get(index);var p=image.point();var block=world.getBlockAt(p.x(),p.y(),p.z());var after=Bukkit.createBlockData(image.data());
                if(!block.getBlockData().equals(after)) {
                    if(block.getState() instanceof Container container)container.getInventory().clear();
                    block.setBlockData(after,false);
                }
                if(image.nbt()!=null)BlockEntitySnapshot.restore(block.getState(),image.nbt());
            }
            if(index<images.size())return;
            world.save(true);var done=working;
            plugin.storage().transaction(db->{WallStore.completeBuild(db,done,System.currentTimeMillis());return null;});
            reset();
        }catch(SQLException|RuntimeException failure) {
            long now=System.currentTimeMillis();if(now-lastWarning>=30000){lastWarning=now;plugin.getLogger().severe("Wall world operation retained for recovery: "+failure.getMessage());}
        }
    }
    private void reset(){working=null;images=null;index=0;}
}
