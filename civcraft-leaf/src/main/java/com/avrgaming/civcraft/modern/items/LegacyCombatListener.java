package com.avrgaming.civcraft.modern.items;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import java.sql.SQLException;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;

public final class LegacyCombatListener implements Listener {
    private final CivCraftModernPlugin plugin;
    private final NativeLegacyItems items;
    private final NamespacedKey bowIdentity;

    public LegacyCombatListener(CivCraftModernPlugin plugin,NativeLegacyItems items) {
        this.plugin=plugin;this.items=items;this.bowIdentity=new NamespacedKey(plugin,"fired-bow-id");
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }

    @EventHandler(priority=EventPriority.LOW,ignoreCancelled=true)
    public void shoot(EntityShootBowEvent event) {
        if(!(event.getEntity() instanceof Player player))return;
        var definition=items.definition(event.getBow()).orElse(null);
        if(definition==null || items.combat(definition.id()).rangedAttack()<=0)return;
        if(wearingMetal(player)) {
            event.setCancelled(true);player.sendMessage(Component.text("Нельзя стрелять из лука в металлической броне."));return;
        }
        event.getProjectile().getPersistentDataContainer().set(bowIdentity,PersistentDataType.STRING,definition.id());
    }

    @EventHandler(priority=EventPriority.LOW,ignoreCancelled=true)
    public void damage(EntityDamageByEntityEvent event) {
        if(event.getDamager() instanceof Arrow towerArrow&&towerArrow.getPersistentDataContainer().has(new NamespacedKey(plugin,"arrow-tower"),PersistentDataType.LONG))return;
        try {
            if(event.getDamager() instanceof Player attacker) {
                var weapon=items.definition(attacker.getInventory().getItemInMainHand()).orElse(null);
                if(weapon==null)event.setDamage(0.5);
                else if(weapon.attack()>0)event.setDamage(LegacyCombatRules.melee(weapon.attack(),items.hasTechnology(attacker,weapon)));
            } else if(event.getDamager() instanceof Arrow arrow && arrow.getShooter() instanceof Player attacker) {
                String id=arrow.getPersistentDataContainer().get(bowIdentity,PersistentDataType.STRING);
                if(id!=null) {
                    var bow=items.require(id);
                    if(wearingMetal(attacker)) {event.setCancelled(true);return;}
                    event.setDamage(LegacyCombatRules.ranged(items.combat(id).rangedAttack(),arrow.getVelocity().lengthSquared(),items.hasTechnology(attacker,bow)));
                }
            }
            if(event.getEntity() instanceof Player defender) {
                for(ItemStack armor:defender.getInventory().getArmorContents()) {
                    var definition=items.definition(armor).orElse(null);
                    if(definition!=null && definition.defense()>0)event.setDamage(LegacyCombatRules.defend(event.getDamage(),definition.defense(),items.hasTechnology(defender,definition)));
                }
            }
        } catch(SQLException exception) {
            event.setCancelled(true);
            plugin.getLogger().warning("Combat technology check failed: "+exception.getMessage());
        }
    }

    @EventHandler(priority=EventPriority.LOW)
    public void death(PlayerDeathEvent event) {
        if(event.getKeepInventory()) {
            ItemStack[] contents=event.getEntity().getInventory().getContents();
            for(int slot=0;slot<contents.length;slot++)if(!damageOnDeath(contents[slot]))contents[slot]=null;
            event.getEntity().getInventory().setContents(contents);
        } else {
            event.getDrops().removeIf(item->!damageOnDeath(item));
        }
    }

    private boolean damageOnDeath(ItemStack stack) {
        var definition=items.definition(stack).orElse(null);
        if(definition==null || items.combat(definition.id()).deathDamage()<=0)return true;
        if(!(stack.getItemMeta() instanceof Damageable meta))return true;
        int maximum=meta.hasMaxDamage()?meta.getMaxDamage():stack.getType().getMaxDurability();
        if(maximum<=0)return true;
        int damage=LegacyCombatRules.deathDamage(maximum,meta.getDamage(),items.combat(definition.id()).deathDamage());
        if(damage<0)return false;
        meta.setDamage(damage);stack.setItemMeta(meta);return true;
    }

    @EventHandler(ignoreCancelled=true)
    public void hold(PlayerItemHeldEvent event) {
        var definition=items.definition(event.getPlayer().getInventory().getItem(event.getNewSlot())).orElse(null);
        if(definition==null || (definition.attack()<=0 && definition.defense()<=0 && items.combat(definition.id()).rangedAttack()<=0))return;
        try {
            if(!items.hasTechnology(event.getPlayer(),definition))event.getPlayer().sendMessage(Component.text("Без нужной технологии урон и защита этого предмета уменьшены вдвое."));
        } catch(SQLException exception) {plugin.getLogger().warning("Item technology warning failed: "+exception.getMessage());}
    }

    private static boolean wearingMetal(Player player) {
        for(ItemStack armor:player.getInventory().getArmorContents()) {
            if(armor==null)continue;
            String name=armor.getType().name();
            if(name.startsWith("CHAINMAIL_") || name.startsWith("IRON_") || name.startsWith("GOLDEN_") || name.startsWith("DIAMOND_") || name.startsWith("NETHERITE_"))return true;
        }
        return false;
    }
}
