package com.avrgaming.civcraft.modern.war;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.build.LegacyDefTemplateLoader;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Sign;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class WarRespawnService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final Set<Long> loaded=new HashSet<>();
    private long lastWarning;
    public WarRespawnService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;
        storage.transaction(db->{WarRespawnStore.install(db);return null;});
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    public void tick() {
        try {
            record Hall(long id,String path,String world,int x,int y,int z) {}
            var halls=storage.query(db->{var out=new ArrayList<Hall>();try(var s=db.createStatement();var r=s.executeQuery("SELECT id,template_path,world,x,y,z FROM structure_builds WHERE status='COMPLETE' AND structure_id IN ('s_capitol','s_townhall')")){while(r.next())out.add(new Hall(r.getLong(1),r.getString(2),r.getString(3),r.getInt(4),r.getInt(5),r.getInt(6)));}return out;});
            for(var hall:halls) {
                if(loaded.contains(hall.id()))continue;
                if(!hall.path().toLowerCase(Locale.ROOT).endsWith(".def"))throw new IllegalArgumentException("Respawn markers in schematic templates are not ported yet");
                var points=new ArrayList<WarRespawnStore.Point>();
                for(var block:new LegacyDefTemplateLoader().load(Path.of(hall.path())).blocks()) {
                    if(block.signLines().isEmpty())continue;
                    var command=block.signLines().getFirst().trim().toLowerCase(Locale.ROOT);
                    if(!Set.of("/respawn","/revive").contains(command))continue;
                    points.add(new WarRespawnStore.Point(hall.id(),command.substring(1),hall.world(),hall.x()+block.x(),hall.y()+block.y(),hall.z()+block.z()));
                }
                storage.transaction(db->{for(var point:points)WarRespawnStore.register(db,point);return null;});
                // Command markers denote free positions rather than player-facing signs.
                for(var point:points) {
                    var world=Bukkit.getWorld(point.world());if(world==null)throw new IllegalStateException("Respawn world unavailable");
                    var block=world.getBlockAt(point.x(),point.y(),point.z());
                    if(block.getState() instanceof Sign sign && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(sign.line(0)).trim().equalsIgnoreCase("/"+point.kind()))block.setType(Material.AIR,false);
                }
                loaded.add(hall.id());
            }
        }catch(Exception failure){if(System.currentTimeMillis()-lastWarning>30000){lastWarning=System.currentTimeMillis();plugin.getLogger().severe("War respawn discovery failed: "+failure.getMessage());}}
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void respawn(PlayerRespawnEvent event) {
        try {
            long now=System.currentTimeMillis();
            var points=storage.query(db->WarRespawnStore.room(db,event.getPlayer().getUniqueId(),now));
            if(points.isEmpty())return;
            var point=points.get(ThreadLocalRandom.current().nextInt(points.size()));
            var world=Bukkit.getWorld(point.world());if(world==null)throw new IllegalStateException("War respawn world unavailable");
            storage.transaction(db->{WarRespawnStore.recordArrival(db,event.getPlayer().getUniqueId(),now);return null;});
            event.setRespawnLocation(new Location(world,point.x()+0.5,point.y()+0.5,point.z()+0.5));
            event.getPlayer().sendMessage(Component.text("Вы возродились в военной комнате капитолия."));
        }catch(Exception failure){plugin.getLogger().severe("War respawn failed: "+failure.getMessage());}
    }
}
