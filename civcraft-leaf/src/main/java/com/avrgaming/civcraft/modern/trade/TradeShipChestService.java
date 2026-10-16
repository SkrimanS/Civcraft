package com.avrgaming.civcraft.modern.trade;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.build.*;
import com.avrgaming.civcraft.modern.town.ConsumptionContainers.Position;
import java.nio.file.Path;
import java.util.*;
import java.sql.*;
import org.bukkit.*;
import org.bukkit.block.Sign;
import net.kyori.adventure.text.Component;

/** Reprocess the original trading ship command markers after completion and level changes. */
public final class TradeShipChestService {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final Map<String,LegacyDefTemplate> templates=new HashMap<>();
    private final Map<Long,String> rendered=new HashMap<>();
    private long lastWarning;
    public record Ship(long id,long town,String world,String path,int x,int y,int z) {}
    public TradeShipChestService(CivCraftModernPlugin plugin,StorageBootstrap storage) {
        this.plugin=plugin;this.storage=storage;
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,40L,20L);
    }
    public List<Ship> ships()throws SQLException {
        return storage.query(db->{var result=new ArrayList<Ship>();
            try(var s=db.createStatement();var r=s.executeQuery("SELECT id,town_id,world,template_path,x,y,z FROM active_structure_builds WHERE structure_id='ti_trade_ship' AND status='COMPLETE' AND town_id IS NOT NULL ORDER BY id")) {
                while(r.next())result.add(new Ship(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getInt(5),r.getInt(6),r.getInt(7)));
            }return result;
        });
    }
    private LegacyDefTemplate template(Ship ship)throws java.io.IOException {
        var result=templates.get(ship.path());if(result==null){result=new LegacyDefTemplateLoader().load(Path.of(ship.path()));templates.put(ship.path(),result);}return result;
    }
    public List<Position> positions(Ship ship,boolean incoming,int upgrade,int level)throws java.io.IOException {
        var result=new LinkedHashSet<Position>();
        for(var block:template(ship).blocks()) {
            var marker=TradeShipMarker.parse(block.signLines()).orElse(null);
            if(marker!=null && marker.kind()==(incoming?TradeShipMarker.Kind.INCOMING:TradeShipMarker.Kind.OUTGOING) && marker.open(upgrade,level))
                result.add(new Position(ship.world(),ship.x()+block.x(),ship.y()+block.y(),ship.z()+block.z()));
        }
        return List.copyOf(result);
    }
    public void tick() {
        try {
            var active=new HashSet<Long>();
            var pending=new HashSet<Long>();
            for(var operation:storage.query(TradeShipOperations::pending))pending.add(operation.before().build());
            for(var ship:ships()) {
                active.add(ship.id());
                if(pending.contains(ship.id()))continue;
                int upgrade=storage.query(db->TradeShipStore.upgrade(db,ship.town()));
                int level=storage.query(db->TradeShipStore.progress(db,ship.id())).level();
                String signature=ship+":"+upgrade+":"+level;
                if(signature.equals(rendered.get(ship.id())))continue;
                render(ship,upgrade,level);rendered.put(ship.id(),signature);
            }
            rendered.keySet().retainAll(active);
        }catch(Exception failure) {
            if(System.currentTimeMillis()-lastWarning>30000){lastWarning=System.currentTimeMillis();plugin.getLogger().severe("Trade ship chest update failed: "+failure.getMessage());}
        }
    }
    public void render(Ship ship,int upgrade,int level)throws java.io.IOException,SQLException {
        var world=Bukkit.getWorld(ship.world());if(world==null)throw new IllegalStateException("Trade ship world unavailable");
        var markers=template(ship).blocks().stream().filter(b->TradeShipMarker.parse(b.signLines()).isPresent()).toList();
        // Validate every marker before changing any block; never overwrite another build's protection.
        for(var b:markers)storage.query(db->{try(var s=db.prepareStatement("SELECT build_id FROM protected_blocks WHERE world=? AND x=? AND y=? AND z=?")) {
            s.setString(1,ship.world());s.setInt(2,ship.x()+b.x());s.setInt(3,ship.y()+b.y());s.setInt(4,ship.z()+b.z());
            try(var r=s.executeQuery()){if(r.next() && r.getLong(1)!=ship.id())throw new SQLException("Trade ship marker overlaps another building");}
        }return null;});
        var paired=new ArrayList<BlockPlacement>();
        var protectedMarkers=new ArrayList<BlockPlacement>();
        for(var b:markers) {
            var marker=TradeShipMarker.parse(b.signLines()).orElseThrow();
            var block=world.getBlockAt(ship.x()+b.x(),ship.y()+b.y(),ship.z()+b.z());
            if(marker.chest()) {
                if(marker.open(upgrade,level)) {
                    // Preserve inventories and PDC when a chest is already present.
                    if(block.getType()!=Material.CHEST)StructureChestBlocks.place(block,b.legacyData());
                    int group=marker.id()+(marker.kind()==TradeShipMarker.Kind.INCOMING?0:10000);
                    paired.add(new BlockPlacement(ship.world(),block.getX(),block.getY(),block.getZ(),"CHEST",List.of("/chest","id:"+group)));
                } else block.setType(Material.AIR,false);
            } else {
                if(!(block.getState() instanceof Sign)) {
                    block.setType(Material.OAK_WALL_SIGN,false);LegacyBlockStateApplier.apply(block,b);
                }
                if(!(block.getState() instanceof Sign sign))throw new IllegalStateException("Trade ship sign missing");
                var lines=marker.text(upgrade,level);for(int i=0;i<4;i++)sign.line(i,Component.text(lines.get(i)));
                sign.update(true,false);
            }
            protectedMarkers.add(new BlockPlacement(ship.world(),block.getX(),block.getY(),block.getZ(),block.getType().name(),b.signLines()));
        }
        StructureChestBlocks.pair(paired);
        storage.recordProtectedBlocks(ship.id(),protectedMarkers);
    }
}
