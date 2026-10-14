package com.avrgaming.civcraft.modern.war;
import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.TileState;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;

public final class BuildingDamageService implements Listener {
    private final CivCraftModernPlugin plugin;private final StorageBootstrap storage;
    private static final Set<String> SOFT=Set.of("GLASS","GLASS_PANE","GRASS_BLOCK","SHORT_GRASS","TALL_GRASS","FERN","LARGE_FERN","DEAD_BUSH","WHEAT","CARROTS","POTATOES","NETHER_WART","REDSTONE_WIRE","REDSTONE_TORCH","REDSTONE_WALL_TORCH","TORCH","WALL_TORCH","REPEATER","LEVER","TRIPWIRE","TRIPWIRE_HOOK","SNOW","VINE","LILY_PAD","DANDELION","BROWN_MUSHROOM","MELON_STEM","PUMPKIN_STEM","FIRE","TNT");
    public BuildingDamageService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;storage.transaction(db->{BuildingDamageStore.install(db);return null;});plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    public void breakBlock(BlockBreakEvent event) {
        var block=event.getBlock();
        try {
            if(!storage.isProtectedBlock(block.getWorld().getName(),block.getX(),block.getY(),block.getZ()))return;
            long now=System.currentTimeMillis();if(!storage.query(db->WarClockStore.active(db,now)))return;
            event.setCancelled(true);var material=block.getType();String name=material.name();
            var control=storage.query(db->ControlPointStore.point(db,block.getWorld().getName(),block.getX(),block.getY(),block.getZ()));
            if(control!=null) {
                var hit=storage.transaction(db->ControlPointStore.hit(db,UUID.randomUUID().toString(),event.getPlayer().getUniqueId(),control,1,now));
                event.getPlayer().sendMessage(Component.text("Контрольный блок: "+hit.health()+"/"+control.maximum()+"."));
                block.getWorld().playSound(block.getLocation(),hit.health()==0?Sound.BLOCK_ANVIL_BREAK:Sound.BLOCK_ANVIL_USE,.4f,1);
                if(hit.health()==0)block.getWorld().spawnParticle(Particle.EXPLOSION,block.getLocation().add(.5,.5,.5),3,.2,.2,.2,0);
                if(hit.townDefeated())plugin.getServer().broadcast(Component.text(hit.civDefeated()?"Все контрольные блоки капитолия разрушены. Цивилизация побеждена в текущем бою.":"Все контрольные блоки ратуши разрушены. Город побеждён в текущем бою."));
                return;
            }
            if(block.getState() instanceof TileState || SOFT.contains(name) || name.endsWith("_LEAVES") || name.endsWith("_BED") || name.endsWith("_SAPLING") || name.endsWith("_HEAD") || name.endsWith("_SKULL"))throw new IllegalArgumentException("Этот блок не подходит для повреждения здания.");
            var health=storage.transaction(db->BuildingDamageStore.hit(db,UUID.randomUUID().toString(),event.getPlayer().getUniqueId(),block.getWorld().getName(),block.getX(),block.getY(),block.getZ(),1,now));
            event.getPlayer().sendMessage(Component.text(health.name()+": здоровье "+health.hitpoints()+"/"+health.maximum()+"."));
            block.getWorld().playSound(block.getLocation(),Sound.BLOCK_ANVIL_USE,.2f,1);
            block.getWorld().spawnParticle(Particle.SMOKE,block.getLocation().add(.5,.5,.5),5,.2,.2,.2,0);
            if(health.hitpoints()==0)event.getPlayer().sendMessage(Component.text("Здание потеряло всё здоровье. Обвал поставлен в очередь."));
        }catch(IllegalArgumentException failure){event.getPlayer().sendMessage(Component.text(failure.getMessage()));}
        catch(SQLException failure){event.setCancelled(true);plugin.getLogger().severe("Building damage failed: "+failure.getMessage());}
    }
    public Optional<String> description(long build)throws SQLException {
        boolean exists=storage.query(db->{try(var s=db.prepareStatement("SELECT 1 FROM building_combat_health WHERE build_id=?")){s.setLong(1,build);try(var r=s.executeQuery()){return r.next();}}});
        if(!exists)return Optional.empty();var health=storage.query(db->BuildingDamageStore.health(db,build));return Optional.of("Здоровье здания: "+health.hitpoints()+"/"+health.maximum()+".");
    }
    public void requireDemolitionSafe(long build)throws SQLException {
        boolean pending=storage.query(db->{try(var s=db.prepareStatement("SELECT 1 FROM building_combat_health WHERE build_id=? AND phase='DESTROY_PENDING'")){s.setLong(1,build);try(var r=s.executeQuery()){return r.next();}}});
        if(pending)throw new IllegalArgumentException("Военное разрушение здания ещё не завершено.");
    }
}
