package com.avrgaming.civcraft.modern.trade;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.build.*;
import java.nio.file.Path;
import java.util.*;
import java.sql.*;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;

/** Materializes original source/hall frame markers; item transfers use a separate barrier. */
public final class GoodieFrameService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final TradeGoodsCatalog catalog;
    private final BonusGoodieItems items;
    private final NamespacedKey identity;
    private final Map<String,List<GoodieFrameMarker>> markers=new HashMap<>();
    private final Set<Long> towers=new HashSet<>();
    private long lastWarning;
    private record Building(long id,long town,String type,String path,String world,int x,int y,int z) {}
    public GoodieFrameService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;catalog=new TradeGoodsCatalog(new java.io.File(plugin.getDataFolder(),"civcraft/data/goods.yml"));
        items=new BonusGoodieItems(plugin,catalog);identity=new NamespacedKey(plugin,"goodie-frame");
        storage.transaction(db->{GoodieTransferStore.install(db);return null;});
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,60L,20L);
    }
    private List<GoodieFrameMarker> markers(Building building)throws java.io.IOException {
        String key=building.type()+":"+building.path();var known=markers.get(key);if(known!=null)return known;
        var result=new ArrayList<GoodieFrameMarker>();
        if(building.path().toLowerCase(Locale.ROOT).endsWith(".def"))for(var block:new LegacyDefTemplateLoader().load(Path.of(building.path())).blocks())GoodieFrameMarker.parse(building.type(),block).ifPresent(result::add);
        var saved=List.copyOf(result);markers.put(key,saved);return saved;
    }
    private void tick() {
        try {
            // Remove invalidated identities even when their origin is no longer a complete building.
            for(var world:Bukkit.getWorlds())for(var frame:world.getEntitiesByClass(ItemFrame.class))if(managed(frame)) {
                String token=items.token(frame.getItem());
                if(token!=null && storage.query(db->BonusGoodieStore.token(db,token))==null)frame.setItem(null,false);
            }
            var buildings=storage.query(db->{var result=new ArrayList<Building>();
                try(var s=db.createStatement();var r=s.executeQuery("SELECT id,town_id,structure_id,template_path,world,x,y,z FROM structure_builds WHERE status='COMPLETE' AND town_id IS NOT NULL AND structure_id IN ('ti_trade_outpost','ti_fishing_boat','s_townhall','s_capitol') ORDER BY id")) {
                    while(r.next())result.add(new Building(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getString(5),r.getInt(6),r.getInt(7),r.getInt(8)));
                }return result;
            });
            for(var building:buildings)try {refresh(building);}catch(Exception failure){warn("Building "+building.id()+" goodie frames failed",failure);}
        }catch(Exception failure){warn("Goodie frame refresh failed",failure);}
    }
    private void warn(String message,Exception failure) {
        if(System.currentTimeMillis()-lastWarning>30000){lastWarning=System.currentTimeMillis();plugin.getLogger().severe(message+": "+failure.getMessage());}
    }
    private void refresh(Building building)throws SQLException,java.io.IOException {
        var world=Bukkit.getWorld(building.world());if(world==null)return;
        for(var marker:markers(building)) {
            int x=building.x()+marker.x(),z=building.z()+marker.z();if(!world.isChunkLoaded(Math.floorDiv(x,16),Math.floorDiv(z,16)))continue;
            var frame=storage.transaction(db->GoodieFrameStore.register(db,building.id(),building.world(),building.x(),building.y(),building.z(),marker));
            if(storage.query(GoodieTransferStore::lockedFrames).contains(frame.key()))continue;
            if(frame.source()&&!towers.contains(building.id())){sourceTower(frame,building);towers.add(building.id());}
            var entity=entity(frame,world);
            BonusGoodieStore.State item=null;
            if(frame.source()) {
                boolean active=storage.query(db->{
                    try(var s=db.prepareStatement("SELECT 1 FROM towns t JOIN administrative_civilizations a ON a.civ_id=t.civ_id WHERE t.id=?")){s.setLong(1,building.town());try(var r=s.executeQuery()){if(r.next())return true;}}
                    return com.avrgaming.civcraft.modern.war.HallValidationStore.state(db,building.id()).valid();
                });
                if(active) {
                    var source=storage.transaction(db->BonusGoodieStore.ensure(db,catalog,building.id()));
                    if(source.kind()==BonusGoodieStore.Kind.SOURCE)item=source;
                }
            }else for(var held:storage.query(db->BonusGoodieStore.framed(db,building.town())))if(held.holder().equals(frame.key())){item=held;break;}
            var current=entity.getItem();
            if(item!=null && !items.matches(current,item)) {
                if(!current.getType().isAir() && items.token(current)==null)throw new IllegalStateException("Managed frame contains an unrelated item");
                entity.setItem(items.create(item),false);
            }else if(item==null && items.token(current)!=null)entity.setItem(null,false);
        }
    }
    private void sourceTower(GoodieFrameStore.Frame frame,Building building)throws SQLException {
        var world=Bukkit.getWorld(frame.world());var placements=new ArrayList<BlockPlacement>();
        var good=storage.query(db->{try(var s=db.prepareStatement("SELECT good_id FROM trade_deposits WHERE build_id=?")){s.setLong(1,building.id());try(var r=s.executeQuery()){return r.next()?r.getString(1):null;}}});
        if(good==null)throw new IllegalArgumentException("У торговой постройки нет месторождения.");
        for(int dy=-1;dy<=1;dy++) {
            int x=frame.x()-1,y=frame.y()+dy,z=frame.z();requireOwn(building.id(),frame.world(),x,y,z);
            world.getBlockAt(x,y,z).setType(Material.BEDROCK,false);placements.add(new BlockPlacement(frame.world(),x,y,z,"BEDROCK",List.of()));
        }
        var block=world.getBlockAt(frame.x(),frame.y()+1,frame.z());requireOwn(building.id(),frame.world(),block.getX(),block.getY(),block.getZ());
        block.setType(Material.OAK_WALL_SIGN,false);var data=(Directional)block.getBlockData();data.setFacing(BlockFace.EAST);block.setBlockData(data,false);
        var sign=(Sign)block.getState();sign.line(0,Component.text(catalog.require(good).name()));sign.update(true,false);
        placements.add(new BlockPlacement(frame.world(),block.getX(),block.getY(),block.getZ(),"OAK_WALL_SIGN",List.of()));storage.recordProtectedBlocks(building.id(),placements);
    }
    private void requireOwn(long build,String world,int x,int y,int z)throws SQLException {
        storage.query(db->{try(var s=db.prepareStatement("SELECT 1 FROM protected_blocks WHERE world=? AND x=? AND y=? AND z=? AND build_id<>?")){s.setString(1,world);s.setInt(2,x);s.setInt(3,y);s.setInt(4,z);s.setLong(5,build);try(var r=s.executeQuery()){if(r.next())throw new IllegalStateException("Goodie frame overlaps another building");}}return null;});
    }
    private ItemFrame entity(GoodieFrameStore.Frame frame,World world)throws SQLException {
        if(frame.entity()!=null && Bukkit.getEntity(UUID.fromString(frame.entity())) instanceof ItemFrame entity && frame.key().equals(key(entity)))return entity;
        var location=new Location(world,frame.x()+.5,frame.y()+.5,frame.z()+.5);
        for(var entity:world.getNearbyEntities(location,1,1,1))if(entity instanceof ItemFrame existing && frame.key().equals(key(existing))) {
            storage.query(db->{GoodieFrameStore.entity(db,frame.key(),existing.getUniqueId());return null;});return existing;
        }
        requireOwn(frame.build(),frame.world(),frame.x(),frame.y(),frame.z());
        var block=world.getBlockAt(frame.x(),frame.y(),frame.z());
        if(!block.getType().isAir() && !(block.getState() instanceof Sign))throw new IllegalStateException("Goodie frame position is occupied");
        block.setType(Material.AIR,false);
        var entity=world.spawn(location,ItemFrame.class,created->{created.setFacingDirection(BlockFace.valueOf(frame.facing()),true);created.setPersistent(true);created.setItemDropChance(0);created.getPersistentDataContainer().set(identity,PersistentDataType.STRING,frame.key());});
        storage.query(db->{GoodieFrameStore.entity(db,frame.key(),entity.getUniqueId());return null;});
        storage.recordProtectedBlocks(frame.build(),List.of(new BlockPlacement(frame.world(),frame.x(),frame.y(),frame.z(),"AIR",List.of())));
        return entity;
    }
    private String key(ItemFrame frame){return frame.getPersistentDataContainer().get(identity,PersistentDataType.STRING);}
    private boolean managed(ItemFrame frame){return key(frame)!=null;}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void damage(EntityDamageEvent e){if(e.getEntity() instanceof ItemFrame frame && managed(frame))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breakFrame(HangingBreakEvent e){if(e.getEntity() instanceof ItemFrame frame && managed(frame))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interact(PlayerInteractEntityEvent e){if(e.getRightClicked() instanceof ItemFrame frame && managed(frame))e.setCancelled(true);}
}
