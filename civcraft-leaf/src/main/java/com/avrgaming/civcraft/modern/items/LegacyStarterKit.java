package com.avrgaming.civcraft.modern.items;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Original civ.yml starting inventory and its SoulBound enhancement. */
public final class LegacyStarterKit implements Listener {
    private final List<ItemStack> kit=new ArrayList<>();
    private final NamespacedKey received;
    private final NamespacedKey soulbound;

    public LegacyStarterKit(CivCraftModernPlugin plugin,NativeLegacyItems items) {
        received=new NamespacedKey(plugin,"starter-kit-received");
        soulbound=new NamespacedKey(plugin,"soulbound");
        var config=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/civ.yml"));
        for(String entry:config.getStringList("global.start_kit")) {
            String[] parts=entry.split(":",-1);
            if(parts.length!=2)throw new IllegalArgumentException("Invalid starting item: "+entry);
            int quantity=Integer.parseInt(parts[1]);
            if(quantity<1 || quantity>64)throw new IllegalArgumentException("Invalid starting quantity: "+entry);
            ItemStack stack=parts[0].startsWith("mat_")?items.create(parts[0],quantity):items.vanilla(Integer.parseInt(parts[0]),quantity);
            var meta=stack.getItemMeta();
            meta.getPersistentDataContainer().set(soulbound,PersistentDataType.BYTE,(byte)1);
            List<Component> lore=meta.lore()==null?new ArrayList<>():new ArrayList<>(meta.lore());
            lore.add(Component.text("Сохраняется после смерти"));meta.lore(lore);stack.setItemMeta(meta);
            kit.add(stack);
        }
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void join(PlayerJoinEvent event) {
        var player=event.getPlayer();
        if(player.hasPlayedBefore() || player.getPersistentDataContainer().has(received,PersistentDataType.BYTE))return;
        // Both the inventory and marker live in the same player-data snapshot.
        for(ItemStack stack:kit)player.getInventory().addItem(stack.clone()).values().forEach(extra->player.getWorld().dropItemNaturally(player.getLocation(),extra));
        player.getPersistentDataContainer().set(received,PersistentDataType.BYTE,(byte)1);
        player.saveData();
        player.sendMessage(Component.text("Вы получили стартовый набор. Книга рецептов: /res book."));
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void death(PlayerDeathEvent event) {
        if(event.getKeepInventory())return;
        var iterator=event.getDrops().iterator();
        while(iterator.hasNext()) {
            ItemStack stack=iterator.next();
            if(stack.hasItemMeta() && stack.getItemMeta().getPersistentDataContainer().has(soulbound,PersistentDataType.BYTE)) {
                event.getItemsToKeep().add(stack);iterator.remove();
            }
        }
    }
}
