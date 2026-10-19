package com.avrgaming.civcraft.modern.build;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.event.*;
import org.bukkit.event.world.ChunkLoadEvent;

/** Translate known default signs in completed, currently loaded buildings, after other journals settle. */
public final class LegacySignMigration implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private record Point(String world,int x,int y,int z,long build,Long camp){}
    private record Area(String world,int x,int z){}
    private final Map<Area,List<Point>> index=new HashMap<>();
    private final Deque<Point> queue=new ArrayDeque<>();
    private final Set<Point> queued=new HashSet<>(),waiting=new HashSet<>();
    private final Set<World> dirty=new HashSet<>();
    private long ticks,nextRefresh;
    private long lastWarning;
    public LegacySignMigration(CivCraftModernPlugin plugin,StorageBootstrap storage) {
        this.plugin=plugin;this.storage=storage;
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,1L,1L);
    }
    private List<Point> read()throws SQLException {
        return storage.query(db->{var out=new ArrayList<Point>();String sql="""
            SELECT p.world,p.x,p.y,p.z,p.build_id,
            CASE WHEN b.structure_id=? THEN (SELECT c.id FROM camps c WHERE c.world=b.world AND c.x=b.x AND c.y=b.y AND c.z=b.z LIMIT 1) ELSE NULL END camp_id
            FROM protected_blocks p JOIN structure_builds b ON b.id=p.build_id
            WHERE b.status='COMPLETE' AND p.material LIKE '%SIGN%'
            """;
            try(var s=db.prepareStatement(sql)) {
                s.setString(1,plugin.settings().campStructureId());
                try(var r=s.executeQuery()){while(r.next())out.add(new Point(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4),r.getLong(5),r.getObject(6)==null?null:r.getLong(6)));}
            }return out;
        });
    }
    private void enqueue(Point point){if(!waiting.contains(point) && queued.add(point))queue.addLast(point);}
    private void retry(Point point){if(waiting.add(point))plugin.getServer().getScheduler().runTaskLater(plugin,()->{waiting.remove(point);enqueue(point);},20L);}
    private boolean unlocked(Point point)throws SQLException {
        return storage.query(db->{
            if(storage.isActiveStructureChunk(point.world(),point.x()>>4,point.z()>>4))throw new IllegalArgumentException("Местность постройки ещё восстанавливается.");
            try(var s=db.prepareStatement("SELECT 1 FROM protected_blocks p JOIN structure_builds b ON b.id=p.build_id WHERE p.world=? AND p.x=? AND p.y=? AND p.z=? AND p.build_id=? AND b.status='COMPLETE'")) {
                s.setString(1,point.world());s.setInt(2,point.x());s.setInt(3,point.y());s.setInt(4,point.z());s.setLong(5,point.build());try(var r=s.executeQuery()){if(!r.next())return false;}
            }
            if(point.camp()!=null)com.avrgaming.civcraft.modern.camp.CampLonghouseStore.requireUnlocked(db,point.camp());
            com.avrgaming.civcraft.modern.trade.TradeShipOperations.requireBuildUnlocked(db,point.build());
            com.avrgaming.civcraft.modern.town.ProductionStore.requireBuildUnlocked(db,point.build());
            com.avrgaming.civcraft.modern.trade.GoodieChestStore.requirePositionsAvailable(db,List.of(new com.avrgaming.civcraft.modern.trade.GoodieChestStore.Position(point.world(),point.x(),point.y(),point.z())));
            return true;
        });
    }
    private void tick() {
        try {
            ticks++;
            if(ticks>=nextRefresh) {
                nextRefresh=ticks+20;var points=read();index.clear();
                for(var point:points){index.computeIfAbsent(new Area(point.world(),point.x()>>4,point.z()>>4),ignored->new ArrayList<>()).add(point);var world=Bukkit.getWorld(point.world());if(world!=null && world.isChunkLoaded(point.x()>>4,point.z()>>4))enqueue(point);}
                nextRefresh=ticks+1200;
            }
            for(int budget=16;budget>0 && !queue.isEmpty();budget--) {
                var point=queue.removeFirst();queued.remove(point);
                var world=Bukkit.getWorld(point.world());if(world==null || !world.isChunkLoaded(point.x()>>4,point.z()>>4))continue;
                if(!(world.getBlockAt(point.x(),point.y(),point.z()).getState() instanceof Sign sign))continue;
                var replacements=new EnumMap<Side,List<String>>(Side.class);
                for(var side:Side.values()){var text=sign.getSide(side).lines().stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();LegacySignText.translation(text).ifPresent(after->replacements.put(side,after));}
                if(replacements.isEmpty())continue;
                try {
                    if(!unlocked(point))continue;
                    for(var entry:replacements.entrySet()){var side=sign.getSide(entry.getKey());for(int i=0;i<4;i++)side.line(i,Component.text(entry.getValue().get(i)).style(side.line(i).style()));}
                    if(!sign.update(false,false)){retry(point);continue;}
                    dirty.add(world);
                }catch(IllegalArgumentException pending){retry(point);}
                catch(SQLException failure){retry(point);throw failure;}
            }
            if(ticks%20==0 || queue.isEmpty())for(var world:List.copyOf(dirty)){world.save(true);dirty.remove(world);}
        }catch(SQLException|RuntimeException failure){warn(failure);}
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void loaded(ChunkLoadEvent event) {
        for(var point:index.getOrDefault(new Area(event.getWorld().getName(),event.getChunk().getX(),event.getChunk().getZ()),List.of()))enqueue(point);
    }
    private void warn(Exception failure){long now=System.currentTimeMillis();if(now-lastWarning>30000){lastWarning=now;plugin.getLogger().warning("Обновление табличек отложено: "+failure.getMessage());}}
}
