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
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.block.Action;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class WarRespawnService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final Set<Long> loaded=new HashSet<>();
    private long lastWarning;
    private final int baseDelay,controlDelay,invalidMinutes;
    private final Map<UUID,Long> lastClick=new HashMap<>();
    public WarRespawnService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;
        var config=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("civcraft/data/war.yml").toFile());
        baseDelay=config.getInt("war.respawn_time",30);controlDelay=config.getInt("war.control_block_respawn_time",30);invalidMinutes=config.getInt("war.invalid_respawn_penalty",10);
        if(baseDelay<0||controlDelay<0||invalidMinutes<0)throw new IllegalArgumentException("Respawn delays must not be negative");
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
                var signs=new ArrayList<WarRespawnStore.SignPoint>();
                for(var block:new LegacyDefTemplateLoader().load(Path.of(hall.path())).blocks()) {
                    if(block.signLines().isEmpty())continue;
                    var command=block.signLines().getFirst().trim().toLowerCase(Locale.ROOT);
                    if(Set.of("/respawndata","/next","/prev").contains(command))signs.add(new WarRespawnStore.SignPoint(hall.id(),command.substring(1),hall.world(),hall.x()+block.x(),hall.y()+block.y(),hall.z()+block.z()));
                    if(!Set.of("/respawn","/revive").contains(command))continue;
                    points.add(new WarRespawnStore.Point(hall.id(),command.substring(1),hall.world(),hall.x()+block.x(),hall.y()+block.y(),hall.z()+block.z()));
                }
                storage.transaction(db->{for(var point:points)WarRespawnStore.register(db,point);for(var sign:signs)WarRespawnStore.registerSign(db,sign);return null;});
                // Command markers denote free positions rather than player-facing signs.
                for(var point:points) {
                    var world=Bukkit.getWorld(point.world());if(world==null)throw new IllegalStateException("Respawn world unavailable");
                    var block=world.getBlockAt(point.x(),point.y(),point.z());
                    if(block.getState() instanceof Sign sign && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(sign.line(0)).trim().equalsIgnoreCase("/"+point.kind()))block.setType(Material.AIR,false);
                }
                loaded.add(hall.id());
            }
            var signs=storage.query(db->{var out=new ArrayList<WarRespawnStore.SignPoint>();try(var s=db.createStatement();var r=s.executeQuery("SELECT p.* FROM war_respawn_signs p JOIN structure_builds b ON b.id=p.build_id WHERE b.status='COMPLETE' AND b.structure_id='s_capitol'")){while(r.next())out.add(new WarRespawnStore.SignPoint(r.getLong("build_id"),r.getString("action"),r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z")));}return out;});
            for(var sign:signs)render(sign);
        }catch(Exception failure){if(System.currentTimeMillis()-lastWarning>30000){lastWarning=System.currentTimeMillis();plugin.getLogger().severe("War respawn discovery failed: "+failure.getMessage());}}
    }
    private WarRespawnStore.SignPoint point(Block block)throws SQLException {
        if(!(block.getState() instanceof Sign))return null;
        return storage.query(db->WarRespawnStore.sign(db,block.getWorld().getName(),block.getX(),block.getY(),block.getZ()));
    }
    public boolean isRespawnSign(Block block) {
        try{return point(block)!=null;}catch(SQLException failure){return false;}
    }
    private void render(WarRespawnStore.SignPoint point)throws SQLException {
        var world=Bukkit.getWorld(point.world());if(world==null)return;
        if(!(world.getBlockAt(point.x(),point.y(),point.z()).getState() instanceof Sign sign))return;
        String[] text;
        if(point.action().equals("respawndata")) {
            var target=storage.query(db->{var options=WarRespawnStore.destinations(db,point.build(),System.currentTimeMillis());int selected=WarRespawnStore.selected(db,point.build());return options.isEmpty()?null:options.get(selected<options.size()?selected:0);});
            text=target==null?new String[]{"Возродиться в","нет доступных мест","",""}:target.camp()==null?new String[]{"Возродиться в",target.name(),"","Нажмите для выхода"}:new String[]{"Возродиться в","Военный лагерь","("+target.x()+","+target.y()+","+target.z()+")","Нажмите для выхода"};
        }else text=new String[]{"",point.action().equals("next")?"Следующее место":"Предыдущее место","",""};
        boolean changed=false;for(int i=0;i<4;i++){var line=Component.text(text[i]);if(!sign.line(i).equals(line)){sign.line(i,line);changed=true;}}if(changed)sign.update(true,false);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void interact(PlayerInteractEvent event) {
        if(event.getHand()!=EquipmentSlot.HAND||event.getClickedBlock()==null||!Set.of(Action.LEFT_CLICK_BLOCK,Action.RIGHT_CLICK_BLOCK).contains(event.getAction()))return;
        try {
            var point=point(event.getClickedBlock());if(point==null)return;event.setCancelled(true);
            var player=event.getPlayer();long now=System.currentTimeMillis();
            if(now-lastClick.getOrDefault(player.getUniqueId(),0L)<250)return;lastClick.put(player.getUniqueId(),now);
            if(!point.action().equals("respawndata"))storage.transaction(db->{WarRespawnStore.cycle(db,point.build(),player.getUniqueId(),point.action().equals("next")?1:-1,now);return null;});
            else exit(player,point.build(),now);
            render(point);
        }catch(IllegalArgumentException failure){event.getPlayer().sendMessage(Component.text(failure.getMessage()));}
        catch(SQLException failure){plugin.getLogger().severe("War room interaction failed: "+failure.getMessage());event.getPlayer().sendMessage(Component.text("Не удалось проверить выход из военной комнаты."));}
    }
    private void exit(Player player,long build,long now)throws SQLException {
        var target=storage.transaction(db->{
            WarRespawnStore.authorize(db,build,player.getUniqueId(),false,now);
            var options=WarRespawnStore.destinations(db,build,now);int selected=WarRespawnStore.selected(db,build);
            if(options.isEmpty())throw new IllegalArgumentException("Нет доступных мест возрождения.");
            if(selected>=options.size()){WarRespawnStore.select(db,build,0);return null;}
            var validity=HallValidationStore.state(db,build);
            int seconds=WarRespawnStore.delaySeconds(baseDelay,controlDelay,WarRespawnStore.destroyedControls(db,build,now),validity.validated()&&!validity.valid(),invalidMinutes,0);
            long remaining=WarRespawnStore.remainingMillis(WarRespawnStore.lastKilled(db,player.getUniqueId()),seconds,now);
            if(remaining>0)throw new IllegalArgumentException("До выхода из военной комнаты: "+remaining/1000+" сек.");
            return options.get(selected);
        });
        if(target==null){player.sendMessage(Component.text("Список мест изменился. Выбор сброшен; нажмите снова."));return;}
        var points=storage.query(db->WarRespawnStore.destinationPoints(db,build,target,System.currentTimeMillis()));
        var point=points.isEmpty()?new WarRespawnStore.Point(target.build()==null?0:target.build(),"revive",target.world(),target.x(),target.y(),target.z()):points.get(ThreadLocalRandom.current().nextInt(points.size()));
        var world=Bukkit.getWorld(point.world());if(world==null)throw new IllegalArgumentException("Мир места возрождения недоступен.");
        player.sendMessage(Component.text("Возрождение: "+target.name()+"."));
        player.teleport(new Location(world,point.x(),point.y(),point.z()));
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
