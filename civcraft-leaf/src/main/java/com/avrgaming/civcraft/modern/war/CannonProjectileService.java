package com.avrgaming.civcraft.modern.war;
import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataType;
public final class CannonProjectileService implements Listener {
    private final CivCraftModernPlugin plugin;private final NamespacedKey effectKey;private final int speed,damage,splash;
    private static final class Flight {final World world;final CannonTrajectory.Point target;CannonTrajectory.Point location;Flight(Location from,Location to){world=from.getWorld();location=point(from);target=point(to);}}
    private final List<Flight> flights=new ArrayList<>();private final List<Firework> effects=new ArrayList<>();
    public CannonProjectileService(CivCraftModernPlugin plugin,int speed,int damage,int splash){if(speed<1||damage<1||splash<0)throw new IllegalArgumentException("Invalid cannon projectile settings");this.plugin=plugin;this.speed=speed;this.damage=damage;this.splash=splash;effectKey=new NamespacedKey(plugin,"cannon-effect");plugin.getServer().getPluginManager().registerEvents(this,plugin);plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,1L,1L);}
    private static CannonTrajectory.Point point(Location location){return new CannonTrajectory.Point(location.getX(),location.getY(),location.getZ());}
    private static Location location(World world,CannonTrajectory.Point point){return new Location(world,point.x(),point.y(),point.z());}
    public void launch(Location from,Location target) {
        if(from.getWorld()!=target.getWorld())throw new IllegalArgumentException("Cannon target is in another world");flights.add(new Flight(from,target));
        var random=java.util.concurrent.ThreadLocalRandom.current();for(int i=0;i<10;i++){var flash=from.clone().add(random.nextInt(5)-2.5,random.nextInt(5)-2.5,random.nextInt(5)-2.5);plugin.getServer().getScheduler().runTaskLater(plugin,()->flash.getWorld().createExplosion(flash,0,false,false),random.nextInt(7));}
    }
    private void tick() {
        effects.removeIf(effect->!effect.isValid());for(var iterator=flights.iterator();iterator.hasNext();) {
            var flight=iterator.next();var step=CannonTrajectory.advance(flight.location,flight.target,speed);flight.location=step.position();var loc=location(flight.world,flight.location);flight.world.createExplosion(loc,0,false,false);
            if(step.arrived()){impact(flight);iterator.remove();}
        }
    }
    private void impact(Flight flight) {
        var center=location(flight.world,flight.target);for(int[] offset:new int[][]{{-3,0},{3,0},{0,-3},{0,3},{0,0}}){var loc=center.clone().add(offset[0],0,offset[1]);fireworks(loc);ignite(loc);}
        for(var player:flight.world.getPlayers())if(CannonTrajectory.splash(point(player.getLocation()),flight.target,splash))player.damage(damage);
    }
    private void ignite(Location center) {
        for(int x=-3;x<3;x++)for(int y=-3;y<3;y++)for(int z=-3;z<3;z++){int height=center.getBlockY()+y;if(height<center.getWorld().getMinHeight()||height>=center.getWorld().getMaxHeight())continue;var block=center.getWorld().getBlockAt(center.getBlockX()+x,height,center.getBlockZ()+z);if(block.getType()==Material.AIR)block.setType(Material.FIRE,true);}
    }
    private void fireworks(Location location) {
        for(int i=0;i<3;i++){var firework=location.getWorld().spawn(location,Firework.class);firework.getPersistentDataContainer().set(effectKey,PersistentDataType.BYTE,(byte)1);var meta=firework.getFireworkMeta();meta.addEffect(FireworkEffect.builder().withColor(Color.ORANGE,Color.YELLOW).flicker(true).with(FireworkEffect.Type.BURST).build());meta.setPower(0);firework.setFireworkMeta(meta);effects.add(firework);plugin.getServer().getScheduler().runTask(plugin,firework::detonate);}
    }
    @EventHandler(priority=EventPriority.LOWEST)
    public void visualDamage(EntityDamageByEntityEvent event){if(event.getDamager() instanceof Firework firework&&firework.getPersistentDataContainer().has(effectKey,PersistentDataType.BYTE))event.setCancelled(true);}
    public void close(){flights.clear();effects.forEach(Entity::remove);effects.clear();}
}
