package com.avrgaming.civcraft.modern.wonder;

import com.avrgaming.civcraft.modern.items.NativeLegacyItems;
import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;

public final class GreatLibraryEnchantments {
    public record Offer(int slot,String name,long cost,Enchantment enchant,int level) {}
    private final List<Offer> offers;
    private static final NamespacedKey PUNCHOUT=new NamespacedKey("civcraft","punchout");
    public GreatLibraryEnchantments(File file) {
        var costs=new HashMap<String,Long>();for(var row:YamlConfiguration.loadConfiguration(file).getMapList("enchants")){double cost=((Number)row.get("cost")).doubleValue();if(!Double.isFinite(cost) || cost<0 || cost!=Math.rint(cost) || cost>1_000_000_000_000L)throw new IllegalArgumentException("Invalid great library price");costs.put(Objects.toString(row.get("id")),(long)cost);}
        offers=List.of(new Offer(0,"Заговор огня II",required(costs,"ench_fire_aspect"),Enchantment.FIRE_ASPECT,2),new Offer(1,"Огнеупорность III",required(costs,"ench_fire_protection"),Enchantment.FIRE_PROTECTION,3),new Offer(2,"Воспламенение",required(costs,"ench_flame"),Enchantment.FLAME,1),new Offer(3,"Пробой зданий",required(costs,"ench_punchout"),null,1));
    }
    private static long required(Map<String,Long> costs,String id){var price=costs.get(id);if(price==null)throw new IllegalArgumentException("Missing great library enchantment: "+id);return price;}
    public Offer offer(int slot){if(slot<0 || slot>=offers.size())throw new IllegalArgumentException("Неизвестное зачарование Великой библиотеки.");return offers.get(slot);}
    public ItemStack apply(ItemStack original,int slot,NativeLegacyItems items) {
        var offer=offer(slot);if(original==null || original.getType().isAir())throw new IllegalArgumentException("Держите предмет для зачарования в руке.");var result=original.clone();
        if(offer.enchant()!=null){if(!offer.enchant().canEnchantItem(result))throw new IllegalArgumentException("Это зачарование не подходит предмету.");result.addUnsafeEnchantment(offer.enchant(),offer.level());return result;}
        if(!Set.of("WOODEN_PICKAXE","STONE_PICKAXE","IRON_PICKAXE","GOLDEN_PICKAXE","DIAMOND_PICKAXE").contains(result.getType().name()) || items.definition(result).isEmpty())throw new IllegalArgumentException("Пробой доступен только киркам CivCraft.");
        var meta=result.getItemMeta();if(meta.getPersistentDataContainer().has(PUNCHOUT,PersistentDataType.BYTE))throw new IllegalArgumentException("У кирки уже есть пробой зданий.");meta.getPersistentDataContainer().set(PUNCHOUT,PersistentDataType.BYTE,(byte)1);var lore=meta.lore()==null?new ArrayList<Component>():new ArrayList<>(meta.lore());lore.add(Component.text("Пробой зданий"));meta.lore(lore);result.setItemMeta(meta);return result;
    }
    /** Legacy inclusive 0..50 roll gives 51%, adding 1..5 damage only to a base hit <=1. */
    public static int structureDamage(ItemStack tool,int base) {
        if(base>1 || tool==null || !tool.hasItemMeta() || !tool.getItemMeta().getPersistentDataContainer().has(PUNCHOUT,PersistentDataType.BYTE))return base;
        var random=ThreadLocalRandom.current();return random.nextInt(100)<=50?Math.addExact(base,random.nextInt(5)+1):base;
    }
}
