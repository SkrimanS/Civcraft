package com.avrgaming.civcraft.modern.war;
import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.build.LegacyDefTemplateLoader;
import java.util.*;
import java.nio.file.Path;
import org.bukkit.*;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
public final class ScoutTowerService {
    private final CivCraftModernPlugin plugin;private final StorageBootstrap storage;private final double range;private final ScoutClock clock;
    private final Map<String,org.bukkit.util.Vector> centers=new HashMap<>();
    private record Tower(long id,long town,long civ,String name,String world,String path,int x,int y,int z) {}
    public ScoutTowerService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws java.sql.SQLException {
        this.plugin=plugin;this.storage=storage;var config=YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("civcraft/data/war.yml").toFile());range=config.getDouble("scout_tower.range",400);
        if(!Double.isFinite(range)||range<0)throw new IllegalArgumentException("Invalid scout range");clock=new ScoutClock(Integer.getInteger("civcraft.test.scout.intervalSeconds",(int)config.getDouble("scout_tower.update",120)));
        storage.transaction(db->{ScoutStore.install(db);return null;});plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,20L,20L);
    }
    private org.bukkit.util.Vector center(String path)throws java.io.IOException {
        var cached=centers.get(path);if(cached!=null)return cached;var template=new LegacyDefTemplateLoader().load(Path.of(path));var value=new org.bukkit.util.Vector(template.sizeX()/2,template.sizeY()/2,template.sizeZ()/2);centers.put(path,value);return value;
    }
    public void toggle(Player player)throws java.sql.SQLException {boolean enabled=storage.transaction(db->ScoutStore.toggle(db,player.getUniqueId()));player.sendMessage(Component.text("Сообщения дозора "+(enabled?"включены.":"выключены.")));}
    public void tick() {
        try {
            var towers=storage.query(db->{var result=new ArrayList<Tower>();try(var s=db.createStatement();var r=s.executeQuery("SELECT b.*,t.civ_id,t.name AS town_name FROM structure_builds b JOIN towns t ON t.id=b.town_id WHERE b.structure_id='s_scouttower' AND b.status NOT IN ('DEMOLISHED','CANCELLED','DEMOLISH_PENDING','ROLLBACK_PENDING') ORDER BY b.id")){while(r.next())result.add(new Tower(r.getLong("id"),r.getLong("town_id"),r.getLong("civ_id"),r.getString("town_name"),r.getString("world"),r.getString("template_path"),r.getInt("x"),r.getInt("y"),r.getInt("z")));}return result;});
            var announced=new HashSet<String>();long now=System.currentTimeMillis();
            for(var tower:towers) {
                if(!clock.ready(tower.id()))continue;var world=Bukkit.getWorld(tower.world());if(world==null)continue;var offset=center(tower.path());var location=new Location(world,tower.x()+offset.getX(),tower.y()+offset.getY(),tower.z()+offset.getZ());
                for(var player:world.getPlayers()) {
                    if(player.isOp()||player.getGameMode()!=GameMode.SURVIVAL||player.getMetadata("vanished").stream().anyMatch(value->value.asBoolean())||location.distanceSquared(player.getLocation())>=range*range)continue;
                    String key=tower.civ()+":"+player.getUniqueId();if(announced.contains(key))continue;
                    var threat=storage.query(db->ScoutStore.threat(db,tower.town(),player.getUniqueId(),now));if(threat==null)continue;
                    String relation=switch(threat){case OUTLAW->"Преступник";case NEUTRAL->"Нейтральный";case HOSTILE->"Враждебный";case WAR->"Война";};var position=player.getLocation();
                    String message="[Дозор] "+player.getName()+" ("+relation+") обнаружен возле города "+tower.name()+": "+position.getBlockX()+", "+position.getBlockY()+", "+position.getBlockZ()+".";
                    for(var recipient:storage.query(db->ScoutStore.recipients(db,tower.civ()))){var online=plugin.getServer().getPlayer(recipient);if(online!=null)online.sendMessage(Component.text(message));}
                    plugin.getLogger().info("[Scout:"+tower.civ()+"] "+message);announced.add(key);
                }
            }
        }catch(Exception failure){plugin.getLogger().severe("Scout tower update failed: "+failure.getMessage());}
    }
}
