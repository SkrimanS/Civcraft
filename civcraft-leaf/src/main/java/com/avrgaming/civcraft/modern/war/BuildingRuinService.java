package com.avrgaming.civcraft.modern.war;
import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/** Journal first, bounded world changes, flush, then terminal status. Resume on server thread. */
public final class BuildingRuinService implements Listener {
    private final CivCraftModernPlugin plugin;private final StorageBootstrap storage;
    private Long current;private List<BuildingRuinStore.Change> changes;private int index;private boolean paused;private long lastWarning;
    public BuildingRuinService(CivCraftModernPlugin plugin,StorageBootstrap storage) {
        this.plugin=plugin;this.storage=storage;plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    private World world(String name){var world=Bukkit.getWorld(name);if(world==null)throw new IllegalStateException("Ruin world is unavailable: "+name);return world;}
    private List<BuildingRuinStore.Change> plan(long build)throws SQLException {
        record Position(String world,int x,int y,int z) {}
        var points=storage.query(db->{var out=new ArrayList<Position>();try(var s=db.prepareStatement("SELECT world,x,y,z FROM protected_blocks WHERE build_id=? ORDER BY world,x,y,z")) {
            s.setLong(1,build);try(var r=s.executeQuery()){while(r.next())out.add(new Position(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4)));}
        }return out;});
        var random=new Random();var result=new ArrayList<BuildingRuinStore.Change>();
        for(var p:points) {
            var block=world(p.world()).getBlockAt(p.x(),p.y(),p.z());
            // Preserve inventories and unknown block-entity NBT; normal chests/signs
            // were also explicitly preserved by the original destruction method.
            if(block.getType().isAir() || block.getState() instanceof TileState)continue;
            var rule=RuinRules.choose(block.getType().name(),()->random.nextInt(100));
            String before=block.getBlockData().getAsString();String after=rule.material().equals(block.getType().name())?before:Material.valueOf(rule.material()).createBlockData().getAsString();
            result.add(new BuildingRuinStore.Change(p.world(),p.x(),p.y(),p.z(),before,after,rule.burst()));
        }return List.copyOf(result);
    }
    public void tick() {
        if(paused)return;
        try {
            if(current==null) {
                current=storage.query(BuildingRuinStore::next);if(current==null)return;
                changes=storage.query(db->BuildingRuinStore.changes(db,current));index=0;
                if(changes==null) {
                    changes=plan(current);var saved=changes;long build=current;
                    storage.transaction(db->{BuildingRuinStore.prepare(db,build,saved);return null;});interrupt("prepared");
                }
            }
            int budget=256;
            while(index<changes.size() && budget-->0) {
                var c=changes.get(index);var block=world(c.world()).getBlockAt(c.x(),c.y(),c.z());
                if(block.getState() instanceof TileState)throw new IllegalStateException("A new block entity occupies a pending ruin block");
                var after=Bukkit.createBlockData(c.after());
                if(!block.getBlockData().matches(after))block.setBlockData(after,false);
                if(c.burst())block.getWorld().spawnParticle(Particle.EXPLOSION,block.getLocation().add(.5,.5,.5),3,.2,.2,.2,0);
                index++;
            }
            if(index<changes.size())return;
            var worlds=new LinkedHashSet<String>();for(var c:changes)worlds.add(c.world());for(var name:worlds)world(name).save(true);
            interrupt("world-saved");long build=current;
            boolean completed=storage.transaction(db->BuildingRuinStore.complete(db,build));
            current=null;changes=null;index=0;
            if(completed){plugin.getServer().broadcast(Component.text("Здание #"+build+" разрушено."));plugin.governments().refreshQueuedProduction();}
        }catch(SQLException|RuntimeException failure) {
            // Retry from the durable plan, including failures during plan creation.
            // Reapplying an after-image is harmless; a half-created in-memory plan is not.
            if(!paused){current=null;changes=null;index=0;}
            if(System.currentTimeMillis()-lastWarning>30000){lastWarning=System.currentTimeMillis();plugin.getLogger().severe("Ruin processing failed; journal retained: "+failure.getMessage());}
        }
    }
    private void interrupt(String phase) {
        if(current!=null && current==Long.getLong("civcraft.test.ruin.build",-1) && phase.equals(System.getProperty("civcraft.test.ruin.interruption"))) {
            paused=true;throw new IllegalStateException("Injected ruin interruption at "+phase);
        }
    }
    private boolean locked(Block block) {
        try{return storage.query(db->{try(var s=db.prepareStatement("SELECT 1 FROM protected_blocks p JOIN structure_builds b ON b.id=p.build_id WHERE p.world=? AND p.x=? AND p.y=? AND p.z=? AND b.status IN ('WAR_DESTROY_PENDING','WAR_DESTROYED','WAR_REPAIR_PENDING') UNION ALL SELECT 1 FROM building_repair_blocks p JOIN building_repair_jobs j ON j.id=p.job_id WHERE p.world=? AND p.x=? AND p.y=? AND p.z=? AND j.state='PREPARED' LIMIT 1")) {
            for(int offset:new int[]{0,4}){s.setString(1+offset,block.getWorld().getName());s.setInt(2+offset,block.getX());s.setInt(3+offset,block.getY());s.setInt(4+offset,block.getZ());}try(var r=s.executeQuery()){return r.next();}
        }});}catch(SQLException failure){plugin.getLogger().warning("Ruin barrier lookup failed: "+failure.getMessage());return true;}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void place(BlockPlaceEvent e){if(locked(e.getBlock()))e.setCancelled(true);if(e instanceof BlockMultiPlaceEvent multi)for(var b:multi.getReplacedBlockStates())if(locked(b.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void physics(BlockPhysicsEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void burn(BlockBurnEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void entity(EntityChangeBlockEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(EntityExplodeEvent e){e.blockList().removeIf(this::locked);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(BlockExplodeEvent e){e.blockList().removeIf(this::locked);}
}
