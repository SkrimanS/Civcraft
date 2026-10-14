package com.avrgaming.civcraft.modern.war;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.build.LegacyDefTemplateLoader;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.*;

/** Scan immutable chunk snapshots in bounded batches, without blocking a server tick. */
public final class HallValidationService {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final boolean enabled;
    private final Map<Long,List<Column>> columns=new HashMap<>();
    private Job job;
    private long nextScan,lastWarning;
    private record Hall(long id,String path,String world,int x,int y,int z) {}
    private record Column(int x,int z) {}
    private static long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    private static final class Job {
        Hall hall;List<Column> columns;Map<Long,ChunkSnapshot> snapshots;int y,minY,depth=1,index;long value;
    }
    public HallValidationService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws java.sql.SQLException {
        this.plugin=plugin;this.storage=storage;
        var config=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("civcraft/data/civ.yml").toFile());
        enabled=String.valueOf(config.get("global.structure_validation","false")).equalsIgnoreCase("true");
        storage.transaction(db->{HallValidationStore.install(db);return null;});
    }
    public void tick() {
        try {
            long now=System.currentTimeMillis();
            if(job==null) {
                if(now<nextScan)return;nextScan=now+1000;
                var hall=storage.query(db->{try(var s=db.prepareStatement("SELECT b.id,b.template_path,b.world,b.x,b.y,b.z FROM structure_builds b LEFT JOIN hall_validation v ON v.build_id=b.id WHERE b.status='COMPLETE' AND b.structure_id IN ('s_capitol','s_townhall') AND (v.checked_at IS NULL OR v.checked_at<?) ORDER BY COALESCE(v.checked_at,0),b.id LIMIT 1")){s.setLong(1,now-60000);try(var r=s.executeQuery()){return r.next()?new Hall(r.getLong(1),r.getString(2),r.getString(3),r.getInt(4),r.getInt(5),r.getInt(6)):null;}}});
                if(hall==null)return;
                if(!enabled){storage.transaction(db->{HallValidationStore.save(db,hall.id(),true,"",now);return null;});return;}
                if(!hall.path().toLowerCase(Locale.ROOT).endsWith(".def"))throw new IllegalArgumentException("Foundation validation of schematic halls is not ported yet");
                var footprint=columns.get(hall.id());
                if(footprint==null){var found=new ArrayList<Column>();for(var block:new LegacyDefTemplateLoader().load(Path.of(hall.path())).blocks())if(block.y()==0&&block.legacyId()!=0)found.add(new Column(hall.x()+block.x(),hall.z()+block.z()));footprint=List.copyOf(found);columns.put(hall.id(),footprint);}
                var world=Bukkit.getWorld(hall.world());if(world==null)throw new IllegalStateException("Validation world unavailable");
                var snapshots=new HashMap<Long,ChunkSnapshot>();for(var column:footprint){int cx=Math.floorDiv(column.x(),16),cz=Math.floorDiv(column.z(),16);snapshots.computeIfAbsent(key(cx,cz),ignored->world.getChunkAt(cx,cz).getChunkSnapshot());}
                job=new Job();job.hall=hall;job.columns=footprint;job.snapshots=snapshots;job.y=hall.y()-1;job.minY=world.getMinHeight();
            }
            int budget=16384;
            while(job!=null&&budget>0) {
                if(job.columns.isEmpty()||job.y<=job.minY){finish(true,"",now);break;}
                var column=job.columns.get(job.index++);var snapshot=job.snapshots.get(key(Math.floorDiv(column.x(),16),Math.floorDiv(column.z(),16)));
                job.value+=HallValidationRules.reinforcement(snapshot.getBlockType(Math.floorMod(column.x(),16),job.y,Math.floorMod(column.z(),16)).name());budget--;
                if(job.index==job.columns.size()) {
                    if(!HallValidationRules.layerValid(job.value,job.columns.size(),job.depth)){finish(false,"Недостаточная опора на высоте "+job.y+": "+job.value+"/"+job.columns.size()+" (нужно "+Math.round(HallValidationRules.requirement(job.depth)*100)+"%).",now);break;}
                    job.y--;job.depth++;job.index=0;job.value=0;
                }
            }
        }catch(Exception failure){job=null;nextScan=System.currentTimeMillis()+30000;if(System.currentTimeMillis()-lastWarning>30000){lastWarning=System.currentTimeMillis();plugin.getLogger().severe("Hall validation failed: "+failure.getMessage());}}
    }
    private void finish(boolean valid,String reason,long now)throws java.sql.SQLException {
        long build=job.hall.id();storage.transaction(db->{HallValidationStore.save(db,build,valid,reason,now);return null;});job=null;
    }
}
