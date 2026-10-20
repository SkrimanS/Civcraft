package com.avrgaming.civcraft.modern.wall;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.war.BuildingDamageStore;
import java.sql.SQLException;
import java.util.UUID;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;

/** Permanent source rules are separate from the PREPARED operation barrier. */
public final class WallColumnService implements Listener {
    private final CivCraftModernPlugin plugin;
    private long lastWarning;
    public WallColumnService(CivCraftModernPlugin plugin){this.plugin=plugin;plugin.getServer().getPluginManager().registerEvents(this,plugin);}
    private WallColumnStore.Protection protection(Block block)throws SQLException {
        return plugin.storage().query(db->WallColumnStore.protecting(db,block.getWorld().getName(),block.getX(),block.getY(),block.getZ()));
    }
    private boolean column(Block block) {
        try{return protection(block)!=null;}catch(SQLException failure){warn(failure);return true;}
    }
    private boolean registered(Block block) {
        try{return plugin.storage().query(db->WallColumnStore.registered(db,block.getWorld().getName(),block.getX(),block.getY(),block.getZ()));}
        catch(SQLException failure){warn(failure);return true;}
    }
    private boolean pistonBlocked(Block block){return registered(block) || column(block);}
    private void warn(SQLException failure){long now=System.currentTimeMillis();if(now-lastWarning>=30000){lastWarning=now;plugin.getLogger().severe("Cannot check wall column protection: "+failure.getMessage());}}
    @EventHandler(priority=EventPriority.NORMAL,ignoreCancelled=true)
    public void breakBlock(BlockBreakEvent event) {
        var block=event.getBlock();var player=event.getPlayer();
        try {
            // Original StructureBlock/RoadBlock handling precedes the synthetic column hit.
            if(plugin.storage().isProtectedBlock(block.getWorld().getName(),block.getX(),block.getY(),block.getZ()))return;
            var owner=protection(block);if(owner==null)return;
            if(player.hasPermission("civcraft.admin"))return;
            if(plugin.storage().query(db->WallColumnStore.sameCivilization(db,player.getUniqueId(),owner.civilization()))){player.sendMessage("Блок находится в колонке стены вашей цивилизации.");return;}
            event.setCancelled(true);
            int damage=com.avrgaming.civcraft.modern.wonder.GreatLibraryEnchantments.structureDamage(player.getInventory().getItemInMainHand(),1);
            var result=plugin.storage().transaction(db->BuildingDamageStore.wallColumnHit(db,"wall-column:"+UUID.randomUUID(),player.getUniqueId(),owner.build(),block.getWorld().getName(),block.getX(),block.getY(),block.getZ(),damage,System.currentTimeMillis()));
            player.sendMessage(result.name()+": здоровье "+result.hitpoints()+"/"+result.maximum()+".");
            block.getWorld().playSound(block.getLocation(),Sound.BLOCK_ANVIL_USE,.2f,1);
            block.getWorld().spawnParticle(Particle.SMOKE,block.getLocation().add(.5,.5,.5),5,.2,.2,.2,0);
            if(result.hitpoints()==0)player.sendMessage("Стена потеряла всё здоровье. Обвал поставлен в очередь.");
        }catch(IllegalArgumentException blocked){player.sendMessage(blocked.getMessage());}
        catch(SQLException failure){event.setCancelled(true);warn(failure);}
    }
    @EventHandler(priority=EventPriority.NORMAL,ignoreCancelled=true)
    public void explosion(EntityExplodeEvent event){if(event.blockList().stream().anyMatch(this::column))event.setCancelled(true);}
    // No BlockExplodeEvent rule: the original plugin did not handle that event.
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void extend(BlockPistonExtendEvent event) {
        var block=event.getBlock().getRelative(event.getDirection());
        for(int i=0;i<13;i++,block=block.getRelative(event.getDirection()))if(block.getType().isAir() && pistonBlocked(block)){event.setCancelled(true);return;}
        if(event.getBlocks().stream().anyMatch(this::pistonBlocked))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void retract(BlockPistonRetractEvent event){if(event.getBlocks().stream().anyMatch(this::pistonBlocked))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOW,ignoreCancelled=true)
    public void burn(BlockBurnEvent event){if(registered(event.getBlock()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.NORMAL,ignoreCancelled=true)
    public void entityChange(EntityChangeBlockEvent event){if(registered(event.getBlock()))event.setCancelled(true);}
}
