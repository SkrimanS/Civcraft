package com.avrgaming.civcraft.modern.trade;

import com.avrgaming.civcraft.modern.items.LegacyItemMaterials;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class BonusGoodieItems {
    private final TradeGoodsCatalog catalog;
    private final LegacyItemMaterials materials;
    private final NamespacedKey token,origin,revision;
    public BonusGoodieItems(JavaPlugin plugin,TradeGoodsCatalog catalog) {
        this.catalog=catalog;token=new NamespacedKey(plugin,"goodie-token");origin=new NamespacedKey(plugin,"goodie-origin");revision=new NamespacedKey(plugin,"goodie-revision");
        try {materials=new LegacyItemMaterials(plugin.getResource("legacy-materials.properties"));}
        catch(java.io.IOException failure){throw new IllegalStateException("Unable to load goodie item materials",failure);}
    }
    public ItemStack create(BonusGoodieStore.State state) {
        var good=catalog.require(state.good());var item=new ItemStack(materials.resolve(good.material(),good.data()),1);
        var meta=item.getItemMeta();meta.displayName(Component.text(good.name()));meta.setMaxStackSize(1);
        meta.lore(List.of(Component.text("Торговый товар"),Component.text("Источник: торговый пост #"+state.origin()),
                Component.text("Перенесите в рамку ратуши для получения бонусов.")));
        meta.getPersistentDataContainer().set(token,PersistentDataType.STRING,state.token());
        meta.getPersistentDataContainer().set(origin,PersistentDataType.LONG,state.origin());
        meta.getPersistentDataContainer().set(revision,PersistentDataType.LONG,state.revision());item.setItemMeta(meta);return item;
    }
    public String token(ItemStack item) {
        if(item==null||!item.hasItemMeta())return null;return item.getItemMeta().getPersistentDataContainer().get(token,PersistentDataType.STRING);
    }
    public Long origin(ItemStack item) {
        if(item==null||!item.hasItemMeta())return null;return item.getItemMeta().getPersistentDataContainer().get(origin,PersistentDataType.LONG);
    }
    public boolean matches(ItemStack item,BonusGoodieStore.State state) {
        if(state==null||item==null||!item.hasItemMeta())return false;
        Long version=item.getItemMeta().getPersistentDataContainer().get(revision,PersistentDataType.LONG);
        return state.token().equals(token(item))&&Long.valueOf(state.origin()).equals(origin(item))&&Long.valueOf(state.revision()).equals(version)&&item.getAmount()==1;
    }
}
