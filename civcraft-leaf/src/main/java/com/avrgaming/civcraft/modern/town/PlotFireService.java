package com.avrgaming.civcraft.modern.town;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.*;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.*;
import org.bukkit.event.block.*;

/** Source plot fire flag applies to ignition/form, independently from structural burn protection. */
public final class PlotFireService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final Set<String> destroyable;
    private long lastWarning;
    private record Point(int x,int y,int z){}
    private enum Kind { STRUCTURE,CAMP,SPECIAL }
    PlotFireService(CivCraftModernPlugin plugin,StorageBootstrap storage) {
        this.plugin=plugin;this.storage=storage;
        destroyable=com.avrgaming.civcraft.modern.war.SiegeCannonCatalog.destroyable(plugin.getDataFolder().toPath().resolve("civcraft/data/structures.yml").toFile());
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    private Map<Point,Kind> nearby(Block block,int radius)throws SQLException {
        return storage.query(db->{var out=new HashMap<Point,Kind>();
            try(var s=db.prepareStatement("SELECT p.x,p.y,p.z,b.structure_id FROM protected_blocks p LEFT JOIN structure_builds b ON b.id=p.build_id WHERE p.world=? AND p.x BETWEEN ? AND ? AND p.y BETWEEN ? AND ? AND p.z BETWEEN ? AND ?")) {
                bind(s,block,radius);try(var r=s.executeQuery()){while(r.next()){String type=r.getString(4);if(plugin.settings().campStructureId().equals(type))out.put(new Point(r.getInt(1),r.getInt(2),r.getInt(3)),Kind.CAMP);else if(type==null || !destroyable.contains(type))out.put(new Point(r.getInt(1),r.getInt(2),r.getInt(3)),Kind.STRUCTURE);}}
            }
            for(var table:List.of("structure_chests","structure_trade_points"))try(var s=db.prepareStatement("SELECT x,y,z FROM "+table+" WHERE world=? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?")) {
                bind(s,block,radius);try(var r=s.executeQuery()){while(r.next())out.putIfAbsent(new Point(r.getInt(1),r.getInt(2),r.getInt(3)),Kind.SPECIAL);}
            }
            return out;
        });
    }
    private static void bind(PreparedStatement s,Block b,int radius)throws SQLException {
        s.setString(1,b.getWorld().getName());s.setInt(2,b.getX()-radius);s.setInt(3,b.getX()+radius);s.setInt(4,b.getY()-radius);s.setInt(5,b.getY()+radius);s.setInt(6,b.getZ()-radius);s.setInt(7,b.getZ()+radius);
    }
    private boolean permits(Block block)throws SQLException {
        return storage.query(db->PlotStore.fire(db,new PlotStore.Key(block.getWorld().getName(),block.getX()>>4,block.getZ()>>4)));
    }
    @EventHandler(priority=EventPriority.NORMAL,ignoreCancelled=true)
    public void ignite(BlockIgniteEvent event) {
        try {
            var block=event.getBlock();var near=nearby(block,1);
            for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++) {
                int height=block.getY()+y;if(height<block.getWorld().getMinHeight() || height>=block.getWorld().getMaxHeight())continue;
                var kind=near.get(new Point(block.getX()+x,height,block.getZ()+z));if(kind==null)continue;
                // The original handler returns at the first structural block even when
                // it is not burnable, before consulting the town's plot flag.
                if(kind==Kind.STRUCTURE){if(block.getRelative(x,y,z).getType().isBurnable())event.setCancelled(true);return;}
                event.setCancelled(true);return;
            }
            if(!permits(block)){event.setCancelled(true);if(event.getPlayer()!=null)event.getPlayer().sendMessage("§cВоспламенение на этом участке запрещено.");}
        }catch(SQLException|RuntimeException failure){event.setCancelled(true);warn(failure);}
    }
    @EventHandler(priority=EventPriority.NORMAL,ignoreCancelled=true)
    public void form(BlockFormEvent event){checkForm(event);}
    @EventHandler(priority=EventPriority.NORMAL,ignoreCancelled=true)
    public void spread(BlockSpreadEvent event){checkForm(event);}
    private void checkForm(BlockFormEvent event) {
        Material type=event.getNewState().getType();if(type!=Material.FIRE && type!=Material.SOUL_FIRE)return;
        try{if(!permits(event.getBlock()))event.setCancelled(true);}catch(SQLException failure){event.setCancelled(true);warn(failure);}
    }
    @EventHandler(priority=EventPriority.LOW,ignoreCancelled=true)
    public void burn(BlockBurnEvent event){try{if(!nearby(event.getBlock(),0).isEmpty())event.setCancelled(true);}catch(SQLException|RuntimeException failure){event.setCancelled(true);warn(failure);}}
    private void warn(Exception failure){long now=System.currentTimeMillis();if(now-lastWarning>30000){lastWarning=now;plugin.getLogger().warning("Unable to check plot fire protection: "+failure.getMessage());}}
}
