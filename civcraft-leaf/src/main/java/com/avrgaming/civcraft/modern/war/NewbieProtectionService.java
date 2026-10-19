package com.avrgaming.civcraft.modern.war;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.io.File;
import java.sql.SQLException;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;

/** Both sides of PvP share one clock; mobs and environmental damage remain unchanged. */
public final class NewbieProtectionService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final long duration;
    private final Map<UUID,Long> lastNotice=new HashMap<>();
    private static final Set<String> HARMFUL=Set.of("blindness","nausea","instant_damage","poison","slowness","mining_fatigue","weakness","wither","hunger","levitation","darkness");
    public NewbieProtectionService(CivCraftModernPlugin plugin)throws SQLException {
        this.plugin=plugin;storage=plugin.storage();
        int minutes=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/civ.yml")).getInt("global.pvp_timer",120);
        if(minutes<0)throw new IllegalArgumentException("Invalid newbie PvP timer");duration=minutes*60000L;
        storage.transaction(db->{NewbieProtectionStore.install(db,System.currentTimeMillis());return null;});
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::expire,20L*30,20L*30);
    }
    public boolean protectedPlayer(UUID player)throws SQLException {return storage.query(db->NewbieProtectionStore.active(db,player,duration,System.currentTimeMillis()));}
    @EventHandler(priority=EventPriority.LOWEST) public void join(PlayerJoinEvent e) {
        try {
            var p=e.getPlayer();storage.getOrCreateResident(p.getUniqueId(),p.getName(),plugin.settings().startingCoins());
            if(protectedPlayer(p.getUniqueId())){p.sendMessage("§7PvP-защита новичка действует "+duration/60000+" минут с регистрации, включая время вне сервера.");p.sendMessage("§7Отключить защиту досрочно: §e/res pvptimer§7. Это действие нельзя отменить.");}
        }catch(SQLException failure){plugin.getLogger().warning("Newbie protection join failed: "+failure.getMessage());}
    }
    private void expire() {
        try {for(var uuid:storage.transaction(db->NewbieProtectionStore.expire(db,duration,System.currentTimeMillis()))){var p=plugin.getServer().getPlayer(uuid);if(p!=null)p.sendMessage("§7Срок PvP-защиты новичка закончился.");}}
        catch(SQLException failure){plugin.getLogger().warning("Newbie protection expiry failed: "+failure.getMessage());}
    }
    private boolean blocked(Player attacker,Player defender)throws SQLException {
        if(protectedPlayer(attacker.getUniqueId())){notice(attacker,"§cСначала отключите свою PvP-защиту: /res pvptimer.");return true;}
        if(protectedPlayer(defender.getUniqueId())){notice(attacker,"§cЭтот игрок находится под PvP-защитой новичка.");return true;}
        return false;
    }
    private void notice(Player player,String text){long now=System.currentTimeMillis();if(now-lastNotice.getOrDefault(player.getUniqueId(),0L)>=1500){lastNotice.put(player.getUniqueId(),now);player.sendMessage(text);}}
    private static Player attacker(Entity entity) {
        if(entity instanceof Player p)return p;
        if(entity instanceof Projectile projectile && !(entity instanceof EnderPearl) && projectile.getShooter() instanceof Player p)return p;
        if(entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p)return p;
        if(entity instanceof Tameable tame && tame.getOwner() instanceof Player p)return p;
        return null;
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void damage(EntityDamageByEntityEvent e) {
        if(!(e.getEntity() instanceof Player defender))return;var attacker=attacker(e.getDamager());if(attacker==null)return;
        try{if(blocked(attacker,defender))e.setCancelled(true);}catch(SQLException failure){e.setCancelled(true);plugin.getLogger().warning("Newbie PvP check failed: "+failure.getMessage());}
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void splash(PotionSplashEvent e) {
        if(!(e.getPotion().getShooter() instanceof Player attacker) || e.getPotion().getEffects().stream().noneMatch(p->HARMFUL.contains(p.getType().getKey().getKey())))return;
        try{for(var entity:e.getAffectedEntities())if(entity instanceof Player defender && blocked(attacker,defender))e.setIntensity(defender,0);}
        catch(SQLException failure){e.setCancelled(true);plugin.getLogger().warning("Newbie potion check failed: "+failure.getMessage());}
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void cloud(AreaEffectCloudApplyEvent e) {
        var cloud=e.getEntity();if(!(cloud.getSource() instanceof Player attacker))return;
        boolean harmful=cloud.getCustomEffects().stream().anyMatch(p->HARMFUL.contains(p.getType().getKey().getKey()));
        if(cloud.getBasePotionType()!=null)harmful|=cloud.getBasePotionType().getPotionEffects().stream().anyMatch(p->HARMFUL.contains(p.getType().getKey().getKey()));
        if(!harmful)return;
        try{for(var iterator=e.getAffectedEntities().iterator();iterator.hasNext();)if(iterator.next() instanceof Player defender && blocked(attacker,defender))iterator.remove();}
        catch(SQLException failure){e.getAffectedEntities().removeIf(Player.class::isInstance);plugin.getLogger().warning("Newbie potion cloud check failed: "+failure.getMessage());}
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void command(PlayerCommandPreprocessEvent e) {
        var parts=e.getMessage().trim().split("\\s+");String root=parts[0].toLowerCase(Locale.ROOT);int colon=root.lastIndexOf(':');if(colon>=0)root="/"+root.substring(colon+1);
        if(!Set.of("/res","/resident").contains(root) || parts.length<2 || !parts[1].equalsIgnoreCase("pvptimer"))return;
        e.setCancelled(true);if(parts.length!=2){e.getPlayer().sendMessage("§eИспользование: /res pvptimer");return;}
        try {
            boolean wasProtected=storage.transaction(db->NewbieProtectionStore.disable(db,e.getPlayer().getUniqueId(),duration,System.currentTimeMillis()));
            e.getPlayer().sendMessage(wasProtected?"§ePvP-защита отключена. Теперь вы можете атаковать игроков и лагеря.":"§7У вас нет действующей PvP-защиты новичка.");
        }catch(SQLException failure){e.getPlayer().sendMessage("§cНе удалось сохранить отключение PvP-защиты.");plugin.getLogger().warning("Newbie protection opt-out failed: "+failure.getMessage());}
    }
    @EventHandler public void quit(PlayerQuitEvent e){lastNotice.remove(e.getPlayer().getUniqueId());}
    @EventHandler(ignoreCancelled=true) public void tab(org.bukkit.event.server.TabCompleteEvent e) {
        if(!(e.getSender() instanceof Player))return;
        var args=e.getBuffer().split("\\s+",-1);if(args.length!=2 || !Set.of("/res","/resident","/civcraft:res","/civcraft:resident").contains(args[0].toLowerCase(Locale.ROOT)))return;
        if("pvptimer".startsWith(args[1].toLowerCase(Locale.ROOT)) && !e.getCompletions().contains("pvptimer"))e.getCompletions().add("pvptimer");
    }
}
