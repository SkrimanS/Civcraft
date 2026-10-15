package com.avrgaming.civcraft.modern.trade;

import java.util.ArrayList;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

final class LibraryEnchantments {
    private LibraryEnchantments() {}
    static ItemStack apply(ItemStack original,LibraryOffer offer,NamespacedKey soulbound) {
        if(original==null || original.getType().isAir())throw new IllegalArgumentException("Держите предмет для зачарования в основной руке.");
        ItemStack result=original.clone();
        if(offer.soulbound()) {
            String material=result.getType().name();
            boolean weapon=material.endsWith("_SWORD") || material.endsWith("_AXE") || material.equals("BOW");
            boolean armor=material.endsWith("_HELMET") || material.endsWith("_CHESTPLATE") || material.endsWith("_LEGGINGS") || material.endsWith("_BOOTS");
            if(!weapon && !armor)throw new IllegalArgumentException("Невыпадение доступно только для оружия и доспехов.");
            var meta=result.getItemMeta();
            if(meta.getPersistentDataContainer().has(soulbound,PersistentDataType.BYTE))throw new IllegalArgumentException("Этот предмет уже сохраняется после смерти.");
            meta.getPersistentDataContainer().set(soulbound,PersistentDataType.BYTE,(byte)1);
            var lore=meta.lore()==null?new ArrayList<Component>():new ArrayList<>(meta.lore());
            lore.add(Component.text("Сохраняется после смерти"));meta.lore(lore);result.setItemMeta(meta);
        } else {
            Enchantment enchant=switch(offer.enchantment()) {
                case "efficiency"->Enchantment.EFFICIENCY;
                case "looting"->Enchantment.LOOTING;
                case "fortune"->Enchantment.FORTUNE;
                case "silk_touch"->Enchantment.SILK_TOUCH;
                case "feather_falling"->Enchantment.FEATHER_FALLING;
                default->throw new IllegalArgumentException("Неизвестное зачарование библиотеки.");
            };
            if(!enchant.canEnchantItem(result))throw new IllegalArgumentException("Это зачарование нельзя применить к данному предмету.");
            if(result.getEnchantmentLevel(enchant)>=offer.level())throw new IllegalArgumentException("У предмета уже есть такое зачарование того же или более высокого уровня.");
            // Original library deliberately used unsafe application (no vanilla conflict check).
            result.addUnsafeEnchantment(enchant,offer.level());
        }
        return result;
    }
}
