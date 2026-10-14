package com.avrgaming.civcraft.modern.war;
import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.build.LegacyDefTemplateLoader;
import java.util.*;
import java.nio.file.Path;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.configuration.file.YamlConfiguration;
public final class ArrowTowerService implements Listener {
    private final CivCraftModernPlugin plugin;private final StorageBootstrap storage;private final NamespacedKey identity;
    private final double range,minimum,power,stop,lighthouse;private final int damage;private final long period;
    private final double teslaRange,teslaMinimum;private final TeslaFireClock teslaClock;
    private final double cannonRange,cannonMinimum;private final TeslaFireClock cannonClock;private final CannonProjectileService cannonProjectiles;
    private record Geometry(double x,double y,double z,List<org.bukkit.util.Vector> turrets) {}
    private record Tower(long id,long town,String type,String world,String path,int x,int y,int z,boolean admin) {}
    private static final class Flight {final Arrow arrow;final Player target;final long town,expires;Location aim;Flight(Arrow arrow,Player target,long town,long now){this.arrow=arrow;this.target=target;this.town=town;expires=now+5000;aim=target.getLocation().clone();}}
    private final Map<String,Geometry> templates=new HashMap<>();private final Map<UUID,Flight> flights=new HashMap<>();
    public ArrowTowerService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws java.sql.SQLException {
        this.plugin=plugin;this.storage=storage;identity=new NamespacedKey(plugin,"arrow-tower");
        var config=YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("civcraft/data/war.yml").toFile());
        range=config.getDouble("arrow_tower.range",100);minimum=config.getDouble("arrow_tower.min_range",5);power=config.getDouble("arrow_tower.power",2.8);stop=config.getDouble("arrow_tower.homing_stop_distance",10);damage=config.getInt("arrow_tower.damage",7);period=(long)(config.getDouble("arrow_tower.fire_rate",1)*20);
        teslaRange=config.getDouble("tesla_tower.range",150);teslaMinimum=config.getDouble("tesla_tower.min_range",10);teslaClock=new TeslaFireClock(config.getInt("tesla_tower.fire_rate",6));
        if(!Double.isFinite(teslaRange)||!Double.isFinite(teslaMinimum)||teslaMinimum<0||teslaRange<=teslaMinimum)throw new IllegalArgumentException("Invalid Tesla range");
        cannonRange=config.getDouble("cannon_tower.range",130);cannonMinimum=config.getDouble("cannon_tower.min_range",30);cannonClock=new TeslaFireClock(config.getInt("cannon_tower.fire_rate",4));
        if(!Double.isFinite(cannonRange)||!Double.isFinite(cannonMinimum)||cannonMinimum<0||cannonRange<=cannonMinimum)throw new IllegalArgumentException("Invalid cannon range");
        cannonProjectiles=new CannonProjectileService(plugin,config.getInt("cannon_tower.speed",6),config.getInt("cannon_tower.damage",8),config.getInt("cannon_tower.splash",30));
        lighthouse=YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("civcraft/data/buffs.yml").toFile()).getMapList("buffs").stream().filter(raw->"buff_great_lighthouse_tower_range".equals(raw.get("id"))).mapToDouble(raw->Double.parseDouble(raw.get("value").toString())).findFirst().orElseThrow(()->new IllegalArgumentException("Missing lighthouse tower range buff"));
        if(!Double.isFinite(lighthouse)||lighthouse<=0)throw new IllegalArgumentException("Invalid lighthouse range multiplier");
        if(!Double.isFinite(range)||!Double.isFinite(minimum)||!Double.isFinite(power)||!Double.isFinite(stop)||range<=minimum||minimum<0||power<=0||stop<0||damage<1||period<1)throw new IllegalArgumentException("Invalid arrow tower settings");
        storage.transaction(db->{OutlawStore.install(db);return null;});plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::fire,period,period);plugin.getServer().getScheduler().runTaskTimer(plugin,this::steer,5L,5L);
        for(var world:plugin.getServer().getWorlds())for(var arrow:world.getEntitiesByClass(Arrow.class))if(arrow.getPersistentDataContainer().has(identity,PersistentDataType.LONG))arrow.remove();
    }
    private Geometry geometry(String path)throws java.io.IOException {
        var result=templates.get(path);if(result!=null)return result;
        var template=new LegacyDefTemplateLoader().load(Path.of(path));var turrets=new ArrayList<org.bukkit.util.Vector>();
        for(var block:template.blocks())if(com.avrgaming.civcraft.modern.build.TowerMarker.firing(block.signLines()))turrets.add(new org.bukkit.util.Vector(block.x(),block.y(),block.z()));
        if(turrets.isEmpty())throw new java.io.IOException("Arrow tower template lacks /towerfire markers");
        result=new Geometry(template.sizeX()/2,template.sizeY()/2,template.sizeZ()/2,List.copyOf(turrets));templates.put(path,result);return result;
    }
    private void fire() {
        try {
            var towers=storage.query(db->{var out=new ArrayList<Tower>();try(var s=db.createStatement();var r=s.executeQuery("SELECT b.*,EXISTS(SELECT 1 FROM administrative_civilizations a WHERE a.civ_id=c.id) AS admin_civ FROM structure_builds b JOIN towns t ON t.id=b.town_id JOIN civilizations c ON c.id=t.civ_id WHERE b.structure_id IN ('s_arrowtower','s_teslatower','s_cannontower') AND b.status='COMPLETE'")){while(r.next())out.add(new Tower(r.getLong("id"),r.getLong("town_id"),r.getString("structure_id"),r.getString("world"),r.getString("template_path"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getBoolean("admin_civ")));}return out;});
            for(var tower:towers) {
                if(storage.query(db->BuildingDamageStore.health(db,tower.id()).hitpoints())==0||(!tower.admin()&&!storage.query(db->HallValidationStore.state(db,tower.id()).valid())))continue;
                var world=Bukkit.getWorld(tower.world());if(world==null)continue;var shape=geometry(tower.path());var center=new Location(world,tower.x()+shape.x(),tower.y()+shape.y(),tower.z()+shape.z());
                boolean tesla=tower.type().equals("s_teslatower");
                boolean cannon=tower.type().equals("s_cannontower");
                double firingRange=storage.query(db->TowerTargetStore.range(db,tower.town(),tesla?teslaRange:cannon?cannonRange:range,lighthouse));
                Player target=null;Location turret=null;double nearest=Double.MAX_VALUE;long now=System.currentTimeMillis();
                for(var player:world.getPlayers()) {
                    if(player.isDead()||player.getGameMode()!=GameMode.SURVIVAL||player.getMetadata("vanished").stream().anyMatch(value->value.asBoolean()))continue;double distance=center.distanceSquared(player.getLocation());
                    if(distance>=nearest||!TowerTargetStore.range(distance,tesla?teslaMinimum:cannon?cannonMinimum:minimum,firingRange)||!storage.query(db->TowerTargetStore.eligible(db,tower.town(),player.getUniqueId(),now)))continue;
                    Location firing=null;double closest=Double.MAX_VALUE;
                    for(var offset:shape.turrets()){var location=new Location(world,tower.x()+offset.getX(),tower.y()+offset.getY(),tower.z()+offset.getZ());double d=location.distanceSquared(player.getLocation());if(d<closest){closest=d;firing=location;}}
                    var eye=player.getEyeLocation();var direction=firing.toVector().subtract(eye.toVector());double length=direction.length();
                    if(tesla){if(player.getLocation().getBlock().getLightFromSky()<15)continue;}
                    else if(length==0||world.rayTraceBlocks(eye,direction.normalize(),length,FluidCollisionMode.NEVER,false)!=null)continue;
                    target=player;turret=firing;nearest=distance;
                }
                if(target==null)continue;
                if(tesla){if(teslaClock.ready(tower.id()))world.strikeLightning(target.getLocation());continue;}
                if(cannon){if(cannonClock.ready(tower.id()))cannonProjectiles.launch(turret,target.getLocation());continue;}
                var targetLocation=target.getLocation().add(0,1,0);turret.add(2*Integer.compare(targetLocation.getBlockX(),turret.getBlockX()),0,2*Integer.compare(targetLocation.getBlockZ(),turret.getBlockZ()));
                var direction=targetLocation.toVector().subtract(turret.toVector());if(direction.lengthSquared()==0)continue;direction.normalize();
                var arrow=world.spawnArrow(turret,direction,(float)power,0);arrow.setVelocity(direction.multiply(power));arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);arrow.getPersistentDataContainer().set(identity,PersistentDataType.LONG,tower.id());flights.put(arrow.getUniqueId(),new Flight(arrow,target,tower.town(),now));
            }
        }catch(Exception failure){plugin.getLogger().severe("Arrow tower firing failed: "+failure.getMessage());}
    }
    private void steer() {
        long now=System.currentTimeMillis();for(var iterator=flights.values().iterator();iterator.hasNext();) {
            var flight=iterator.next();var arrow=flight.arrow;
            if(!arrow.isValid()||arrow.isOnGround()||now>flight.expires||!flight.target.isOnline()||flight.target.isDead()||arrow.getWorld()!=flight.target.getWorld()){arrow.remove();iterator.remove();continue;}
            double distance=arrow.getLocation().distanceSquared(flight.aim);if(distance<1){arrow.remove();iterator.remove();continue;}if(distance>stop*stop)flight.aim=flight.target.getLocation().add(0,1,0);
            var direction=flight.aim.toVector().subtract(arrow.getLocation().toVector());if(direction.lengthSquared()>0)arrow.setVelocity(direction.normalize().multiply(power));
        }
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    public void damage(EntityDamageByEntityEvent event) {
        if(!(event.getDamager() instanceof Arrow arrow)||!arrow.getPersistentDataContainer().has(identity,PersistentDataType.LONG))return;
        var flight=flights.remove(arrow.getUniqueId());arrow.remove();if(flight==null){event.setCancelled(true);return;}
        try{if(event.getEntity() instanceof Player player&&storage.query(db->TowerTargetStore.friendly(db,flight.town,player.getUniqueId()))){event.setCancelled(true);return;}event.setDamage(damage);}
        catch(java.sql.SQLException failure){event.setCancelled(true);plugin.getLogger().severe("Arrow tower damage failed: "+failure.getMessage());}
    }
    @EventHandler public void hit(ProjectileHitEvent event){if(event.getEntity() instanceof Arrow arrow&&event.getHitBlock()!=null&&arrow.getPersistentDataContainer().has(identity,PersistentDataType.LONG)){flights.remove(arrow.getUniqueId());arrow.remove();}}
    public void close(){flights.values().forEach(f->f.arrow.remove());flights.clear();cannonProjectiles.close();}
}
