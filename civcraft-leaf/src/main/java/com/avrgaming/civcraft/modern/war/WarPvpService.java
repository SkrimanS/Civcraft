package com.avrgaming.civcraft.modern.war;
import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
public final class WarPvpService implements Listener {
    private final CivCraftModernPlugin plugin;private final StorageBootstrap storage;
    public WarPvpService(CivCraftModernPlugin plugin,StorageBootstrap storage){this.plugin=plugin;this.storage=storage;plugin.getServer().getPluginManager().registerEvents(this,plugin);}
    private boolean allowed(Player attacker,Player defender)throws SQLException {
        var loc=defender.getLocation();var result=storage.query(db->WarPvpStore.check(db,attacker.getUniqueId(),defender.getUniqueId(),loc.getWorld().getName(),Math.floorDiv(loc.getBlockX(),16),Math.floorDiv(loc.getBlockZ(),16),System.currentTimeMillis()));
        if(result==WarPvpStore.Decision.ALLOWED)return true;
        attacker.sendMessage(Component.text(switch(result){case NEUTRAL_IN_WARZONE->"Нейтральный участник не может атаковать бойцов в этой зоне войны.";case NOT_AT_WAR->"В этом привате вы не воюете с цивилизацией игрока "+defender.getName()+".";default->"PvP в этом привате запрещено.";}));return false;
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void damage(EntityDamageByEntityEvent e) {
        if(!(e.getEntity() instanceof Player defender))return;
        // Teleportation damage belongs to the thrower, not to a PvP attack.
        if(e.getDamager() instanceof EnderPearl)return;
        Player attacker=e.getDamager() instanceof Player p?p:e.getDamager() instanceof Projectile projectile&&projectile.getShooter() instanceof Player p?p:null;
        if(attacker==null)return;
        try{if(!allowed(attacker,defender))e.setCancelled(true);}catch(SQLException failure){e.setCancelled(true);plugin.getLogger().warning("PvP authority check failed: "+failure.getMessage());}
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void splash(PotionSplashEvent e) {
        if(!(e.getPotion().getShooter() instanceof Player attacker))return;
        var effects=e.getPotion().getEffects().stream().map(effect->effect.getType().getKey().getKey()).toList();
        if(effects.contains("invisibility")){e.setCancelled(true);return;}
        if(effects.stream().noneMatch(Set.of("blindness","nausea","instant_damage","poison","slowness","mining_fatigue","weakness","wither")::contains))return;
        try{for(var entity:e.getAffectedEntities())if(entity instanceof Player defender&&!allowed(attacker,defender)){e.setCancelled(true);return;}}catch(SQLException failure){e.setCancelled(true);plugin.getLogger().warning("Potion PvP check failed: "+failure.getMessage());}
    }
    public void command(Player player,String[] args)throws SQLException {
        var resident=storage.findResident(player.getUniqueId()).orElseThrow();if(resident.townId()==null)throw new IllegalArgumentException("Вы не состоите в городе.");long town=resident.townId();String action=args.length>2?args[2].toLowerCase(Locale.ROOT):"list";
        storage.query(db->{OutlawStore.authority(db,town,player.getUniqueId());return null;});
        if(action.equals("list")) {
            var names=storage.query(db->{var out=new ArrayList<String>();try(var s=db.prepareStatement("SELECT r.name FROM town_outlaws o JOIN residents r ON r.uuid=o.uuid WHERE o.town_id=? ORDER BY r.name")){s.setLong(1,town);try(var r=s.executeQuery()){while(r.next())out.add(r.getString(1));}}return out;});player.sendMessage(Component.text("Преступники города: "+(names.isEmpty()?"нет":String.join(", ",names))));return;
        }
        if(args.length>=4&&List.of("addall","removeall","addallciv","removeallciv").contains(action)) {
            boolean civ=action.endsWith("civ"),add=action.startsWith("add");String name=String.join(" ",Arrays.copyOfRange(args,3,args.length));
            int count=storage.transaction(db->{var targets=new ArrayList<UUID>();String sql=civ?"SELECT r.uuid FROM residents r JOIN towns t ON t.id=r.town_id JOIN civilizations c ON c.id=t.civ_id WHERE c.name=? COLLATE NOCASE":"SELECT r.uuid FROM residents r JOIN towns t ON t.id=r.town_id WHERE t.name=? COLLATE NOCASE";try(var s=db.prepareStatement(sql)){s.setString(1,name);try(var r=s.executeQuery()){while(r.next())targets.add(UUID.fromString(r.getString(1)));}}return OutlawStore.group(db,town,player.getUniqueId(),targets,add,System.currentTimeMillis());});
            player.sendMessage(Component.text("Список преступников обновлён. Изменено записей: "+count+"."));return;
        }
        if(args.length!=4||!List.of("add","remove").contains(action))throw new IllegalArgumentException("Использование: /town outlaw list|add|remove <ник>, addall|removeall <город>, addallciv|removeallciv <цивилизация>");
        var target=storage.findResidentByName(args[3]).orElseThrow(()->new IllegalArgumentException("Житель не найден."));
        storage.transaction(db->{if(action.equals("add"))OutlawStore.add(db,town,player.getUniqueId(),target.uuid(),System.currentTimeMillis());else OutlawStore.remove(db,town,player.getUniqueId(),target.uuid());return null;});
        player.sendMessage(Component.text(action.equals("add")?"Житель "+target.name()+" объявлен преступником города. Статус действует через одну секунду.":"Житель "+target.name()+" удалён из списка преступников."));
        var online=plugin.getServer().getPlayer(target.uuid());if(online!=null)online.sendMessage(Component.text(action.equals("add")?"Город "+storage.findTown(town).orElseThrow().name()+" объявляет вас преступником. Через одну секунду PvP в его приватах будет разрешено.":"Ваш статус преступника в городе снят."));
    }
}
