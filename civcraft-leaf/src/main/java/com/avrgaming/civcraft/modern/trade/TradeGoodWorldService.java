package com.avrgaming.civcraft.modern.trade;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.io.File;
import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.event.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import net.kyori.adventure.text.Component;

/** Populate planned new overworld chunks without generating the entire map at startup. */
public final class TradeGoodWorldService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final TradeGoodsCatalog catalog;
    private final TradeGoodGeneration generator;
    private String worldName;
    private final Set<TradeMarkerStore.Position> protectedMarkers=new HashSet<>();
    private final Set<TradeGoodGeneration.Chunk> queued=new LinkedHashSet<>();
    private long lastWarning;
    public TradeGoodWorldService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;var file=new File(plugin.getDataFolder(),"civcraft/data/goods.yml");
        catalog=new TradeGoodsCatalog(file);generator=TradeGoodGeneration.load(file,catalog);
        storage.transaction(db->{TradeMarkerStore.install(db);return null;});
        for(var job:storage.query(db->TradeMarkerStore.jobs(db,false)))protectedMarkers.addAll(TradeMarkerStore.positions(job));
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        for(var world:plugin.getServer().getWorlds())if(world.getEnvironment()==World.Environment.NORMAL){initialize(world);break;}
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,40L,1L);
    }
    private void initialize(World world)throws SQLException {
        if(worldName!=null)return;
        String name=world.getName();
        storage.transaction(db->{if(!TradeGenerationStore.initialized(db,name))TradeGenerationStore.initialize(db,name,generator.settings().seed(),generator.plan());return null;});
        worldName=name;queued.addAll(storage.query(db->TradeGenerationStore.requests(db,name)));
    }
    @EventHandler public void initialize(WorldInitEvent event) {
        if(event.getWorld().getEnvironment()!=World.Environment.NORMAL)return;
        try {initialize(event.getWorld());}catch(SQLException failure){plugin.getLogger().severe("Unable to initialize trade generation world: "+failure.getMessage());}
    }
    @EventHandler public void load(ChunkLoadEvent event) {
        if(Boolean.getBoolean("civcraft.test.tradeGoods.disableNewChunks"))return;
        if(event.isNewChunk() && event.getWorld().getName().equals(worldName)) {
            int x=event.getChunk().getX(),z=event.getChunk().getZ();
            try {
                if(storage.query(db->TradeGenerationStore.pick(db,worldName,x,z))==null)return;
                storage.query(db->{TradeGenerationStore.request(db,worldName,x,z);return null;});queued.add(new TradeGoodGeneration.Chunk(x,z));
            }
            catch(SQLException failure){plugin.getLogger().severe("Unable to queue new trade good chunk: "+failure.getMessage());}
        }
    }
    private void tick() {
        try {
            if(worldName==null) {
                for(var world:plugin.getServer().getWorlds())if(world.getEnvironment()==World.Environment.NORMAL){initialize(world);break;}
                if(worldName==null)return;
            }
            var pending=storage.query(db->TradeMarkerStore.jobs(db,true));
            if(!pending.isEmpty()){finish(pending.getFirst());return;}
            if(queued.isEmpty())return;var chunk=queued.iterator().next();
            var world=Bukkit.getWorld(worldName);if(world==null)return;
            if(!world.isChunkLoaded(chunk.x(),chunk.z())){queued.remove(chunk);queued.add(chunk);return;}
            var pick=storage.query(db->TradeGenerationStore.pick(db,worldName,chunk.x(),chunk.z()));if(pick==null){discard(chunk);return;}
            boolean occupied=storage.query(db->{try(var s=db.prepareStatement("SELECT 1 FROM town_claims WHERE world=? AND chunk_x=? AND chunk_z=? UNION ALL SELECT 1 FROM structure_chunks WHERE world=? AND chunk_x=? AND chunk_z=? LIMIT 1")) {
                s.setString(1,worldName);s.setInt(2,chunk.x());s.setInt(3,chunk.z());s.setString(4,worldName);s.setInt(5,chunk.x());s.setInt(6,chunk.z());try(var r=s.executeQuery()){return r.next();}
            }});if(occupied){discard(chunk);return;}
            int x=Math.addExact(Math.multiplyExact(chunk.x(),16),8),z=Math.addExact(Math.multiplyExact(chunk.z(),16),8);
            var ground=world.getHighestBlockAt(x,z,HeightMap.WORLD_SURFACE);int y=ground.getY()+1;
            if(y+2>=world.getMaxHeight()){discard(chunk);return;}
            String good=ground.getType()==Material.WATER?pick.water():pick.land();
            int face=Math.floorMod(generator.settings().seed()^chunk.x()^chunk.z(),4);
            var job=storage.transaction(db->{var prepared=TradeMarkerStore.prepare(db,catalog,good,worldName,x,y,z,face);TradeGenerationStore.removeRequest(db,worldName,chunk);return prepared;});
            queued.remove(chunk);
            if(job!=null){protectedMarkers.addAll(TradeMarkerStore.positions(job));finish(job);}
        }catch(Exception failure) {
            if(System.currentTimeMillis()-lastWarning>30000){lastWarning=System.currentTimeMillis();plugin.getLogger().severe("Trade good generation failed; journal retained: "+failure.getMessage());}
        }
    }
    private void discard(TradeGoodGeneration.Chunk chunk)throws SQLException {
        storage.query(db->{TradeGenerationStore.removeRequest(db,worldName,chunk);return null;});queued.remove(chunk);
    }
    private void finish(TradeMarkerStore.Job job)throws SQLException {
        var world=Bukkit.getWorld(job.world());if(world==null)throw new IllegalStateException("Trade marker world unavailable");
        var positions=TradeMarkerStore.positions(job);
        for(var position:positions) {
            boolean foreign=storage.query(db->{try(var s=db.prepareStatement("SELECT 1 FROM protected_blocks WHERE world=? AND x=? AND y=? AND z=?")) {
                s.setString(1,position.world());s.setInt(2,position.x());s.setInt(3,position.y());s.setInt(4,position.z());try(var r=s.executeQuery()){return r.next();}
            }});if(foreign)throw new IllegalStateException("Trade marker overlaps a structure");
        }
        for(int i=0;i<3;i++){var p=positions.get(i);world.getBlockAt(p.x(),p.y(),p.z()).setType(Material.BEDROCK,false);}
        var p=positions.get(3);var block=world.getBlockAt(p.x(),p.y(),p.z());block.setType(Material.OAK_WALL_SIGN,false);
        var data=(Directional)block.getBlockData();data.setFacing(new BlockFace[]{BlockFace.NORTH,BlockFace.EAST,BlockFace.SOUTH,BlockFace.WEST}[job.face()]);block.setBlockData(data,false);
        var sign=(Sign)block.getState();sign.line(0,Component.text("Торговый товар"));sign.line(1,Component.text("----"));sign.line(2,Component.text(catalog.require(job.good()).name()));sign.line(3,Component.empty());sign.update(true,false);
        world.save(true);storage.transaction(db->{TradeMarkerStore.complete(db,job.deposit());return null;});
    }
    private boolean protectedBlock(Block block){return protectedMarkers.contains(new TradeMarkerStore.Position(block.getWorld().getName(),block.getX(),block.getY(),block.getZ()));}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void destroy(BlockBreakEvent e){if(protectedBlock(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void place(BlockPlaceEvent e){if(protectedBlock(e.getBlockPlaced()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void flow(BlockFromToEvent e){if(protectedBlock(e.getToBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void piston(BlockPistonExtendEvent e){if(protectedBlock(e.getBlock().getRelative(e.getDirection()))||e.getBlocks().stream().anyMatch(this::protectedBlock))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void piston(BlockPistonRetractEvent e){if(e.getBlocks().stream().anyMatch(b->protectedBlock(b)||protectedBlock(b.getRelative(e.getDirection().getOppositeFace()))))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void burn(BlockBurnEvent e){if(protectedBlock(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void physics(BlockPhysicsEvent e){if(protectedBlock(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(EntityExplodeEvent e){e.blockList().removeIf(this::protectedBlock);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(BlockExplodeEvent e){e.blockList().removeIf(this::protectedBlock);}
}
