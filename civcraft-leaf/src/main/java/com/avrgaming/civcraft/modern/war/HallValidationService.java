package com.avrgaming.civcraft.modern.war;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.build.LegacyDefTemplateLoader;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;

/** Scan immutable chunk snapshots in bounded batches, without blocking a server tick. */
public final class HallValidationService {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final boolean enabled;
    private final Map<Long,List<Column>> columns=new HashMap<>();
    private Job job;
    private long nextScan,lastWarning;
    private final Set<String> exempt=new HashSet<>();
    private final ArrayDeque<Request> requests=new ArrayDeque<>();
    private final Set<UUID> pending=new HashSet<>();
    private record Request(Hall hall,UUID player) {}
    private record Hall(long id,String path,String world,int x,int y,int z) {}
    private record Column(int x,int z) {}
    private static long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    private static final class Job {
        Hall hall;UUID requester;List<Column> columns;Map<Long,ChunkSnapshot> snapshots;int y,minY,depth=1,index;long value;
    }
    public HallValidationService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws java.sql.SQLException {
        this.plugin=plugin;this.storage=storage;
        var config=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("civcraft/data/civ.yml").toFile());
        enabled=String.valueOf(config.get("global.structure_validation","false")).equalsIgnoreCase("true");
        for(String file:List.of("structures.yml","wonders.yml")) {
            var definitions=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("civcraft/data/"+file).toFile());
            for(String root:List.of("structures","wonders"))for(var definition:definitions.getMapList(root))if(String.valueOf(definition.get("ignore_floating")).equalsIgnoreCase("true"))exempt.add(String.valueOf(definition.get("id")));
        }
        storage.transaction(db->{HallValidationStore.install(db);return null;});
    }
    public void tick() {
        try {
            long now=System.currentTimeMillis();
            if(job==null) {
                if(requests.isEmpty()&&now<nextScan)return;nextScan=now+1000;
                var request=requests.pollFirst();
                var hall=request!=null?request.hall():storage.query(db->{try(var s=db.prepareStatement("SELECT b.id,b.template_path,b.world,b.x,b.y,b.z FROM structure_builds b LEFT JOIN hall_validation v ON v.build_id=b.id WHERE b.status='COMPLETE' AND b.town_id IS NOT NULL AND LOWER(b.template_path) LIKE '%.def' AND (v.checked_at IS NULL OR v.checked_at<?) AND "+com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.visible(db,"structure_builds","b.id")+" ORDER BY COALESCE(v.checked_at,0),b.id LIMIT 1")){s.setLong(1,now-60000);try(var r=s.executeQuery()){return r.next()?new Hall(r.getLong(1),r.getString(2),r.getString(3),r.getInt(4),r.getInt(5),r.getInt(6)):null;}}});
                if(hall==null)return;
                job=new Job();job.hall=hall;job.requester=request==null?null:request.player();
                boolean skip=storage.query(db->{try(var s=db.prepareStatement("SELECT structure_id FROM structure_builds WHERE id=?")){s.setLong(1,hall.id());try(var r=s.executeQuery()){return r.next()&&exempt.contains(r.getString(1));}}});
                if(!enabled||skip){finish(true,"",now);return;}
                if(!hall.path().toLowerCase(Locale.ROOT).endsWith(".def"))throw new IllegalArgumentException("Проверка фундамента ратуш schematic ещё не перенесена");
                var footprint=columns.get(hall.id());
                if(footprint==null){var found=new ArrayList<Column>();for(var block:new LegacyDefTemplateLoader().load(Path.of(hall.path())).blocks())if(block.y()==0&&block.legacyId()!=0)found.add(new Column(hall.x()+block.x(),hall.z()+block.z()));footprint=List.copyOf(found);columns.put(hall.id(),footprint);}
                var world=Bukkit.getWorld(hall.world());if(world==null)throw new IllegalStateException("Validation world unavailable");
                var snapshots=new HashMap<Long,ChunkSnapshot>();for(var column:footprint){int cx=Math.floorDiv(column.x(),16),cz=Math.floorDiv(column.z(),16);snapshots.computeIfAbsent(key(cx,cz),ignored->world.getChunkAt(cx,cz).getChunkSnapshot());}
                job.columns=footprint;job.snapshots=snapshots;job.y=hall.y()-1;job.minY=world.getMinHeight();
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
        }catch(Exception failure){if(job!=null&&job.requester!=null){pending.remove(job.requester);var player=Bukkit.getPlayer(job.requester);if(player!=null)player.sendMessage(Component.text("Не удалось проверить опору здания."));}job=null;nextScan=System.currentTimeMillis()+30000;if(System.currentTimeMillis()-lastWarning>30000){lastWarning=System.currentTimeMillis();plugin.getLogger().severe("Structure validation failed: "+failure.getMessage());}}
    }
    private void finish(boolean valid,String reason,long now)throws java.sql.SQLException {
        long build=job.hall.id();var player=job.requester==null?null:Bukkit.getPlayer(job.requester);boolean override=player!=null&&player.isOp();
        storage.transaction(db->{if(job.requester!=null)HallValidationStore.saveManual(db,build,valid||override,override?"":reason,now);else HallValidationStore.save(db,build,valid,reason,now);return null;});
        if(job.requester!=null){pending.remove(job.requester);if(player!=null){if(!reason.isBlank())player.sendMessage(Component.text(reason));if(override)player.sendMessage(Component.text("Оператор: результат проверки опоры принят принудительно."));player.sendMessage(Component.text(valid||override?"Проверка завершена: опора здания валидна.":"Проверка завершена: опора здания невалидна."));}}
        job=null;
    }
    public void request(Player player)throws java.sql.SQLException {
        if(pending.contains(player.getUniqueId()))throw new IllegalArgumentException("Ваша проверка уже выполняется.");
        var location=player.getLocation();
        populateGeometry(location.getWorld().getName());
        var hall=storage.query(db->{long id=HallValidationStore.nearestForManual(db,player.getUniqueId(),location.getWorld().getName(),location.getX(),location.getY(),location.getZ(),System.currentTimeMillis());try(var s=db.prepareStatement("SELECT template_path,world,x,y,z,structure_id FROM structure_builds WHERE id=?")){s.setLong(1,id);try(var r=s.executeQuery()){r.next();if(exempt.contains(r.getString(6)))throw new IllegalArgumentException("Это здание освобождено от проверки опоры.");return new Hall(id,r.getString(1),r.getString(2),r.getInt(3),r.getInt(4),r.getInt(5));}}});
        if(!hall.path().toLowerCase(Locale.ROOT).endsWith(".def"))throw new IllegalArgumentException("Проверка этого формата шаблона ещё не перенесена.");
        requests.addLast(new Request(hall,player.getUniqueId()));pending.add(player.getUniqueId());
        player.sendMessage(Component.text("Проверка опоры здания #"+hall.id()+" поставлена в очередь."));
    }
    private void populateGeometry(String world)throws java.sql.SQLException {
        record Source(long build,String path) {}
        var sources=storage.query(db->{var out=new ArrayList<Source>();try(var s=db.prepareStatement("SELECT b.id,b.template_path FROM structure_builds b LEFT JOIN structure_validation_geometry g ON g.build_id=b.id WHERE b.world=? AND g.build_id IS NULL AND b.status IN ('QUEUED','BUILDING','COMPLETE') AND LOWER(b.template_path) LIKE '%.def' AND "+com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.visible(db,"structure_builds","b.id"))){s.setString(1,world);try(var r=s.executeQuery()){while(r.next())out.add(new Source(r.getLong(1),r.getString(2)));}}return out;});
        for(var source:sources)try(var reader=java.nio.file.Files.newBufferedReader(Path.of(source.path()))) {
            String header=reader.readLine();if(header==null)throw new java.io.IOException("Empty template");var dimensions=header.split(";",-1);if(dimensions.length!=3)throw new java.io.IOException("Invalid template dimensions");
            int width=Integer.parseInt(dimensions[0].trim()),depth=Integer.parseInt(dimensions[2].trim());storage.transaction(db->{HallValidationStore.geometry(db,source.build(),width,depth);return null;});
        }catch(java.io.IOException|NumberFormatException failure){throw new IllegalArgumentException("Не удалось прочитать размеры ближайших зданий.",failure);}
    }
    public String description(long build)throws java.sql.SQLException {
        var state=storage.query(db->HallValidationStore.state(db,build));
        return !state.validated()?"Опора: ещё не проверена.":"Валидность: "+(state.valid()?"валидно":"невалидно — "+state.reason());
    }
}
