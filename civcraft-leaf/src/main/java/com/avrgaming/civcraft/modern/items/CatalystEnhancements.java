package com.avrgaming.civcraft.modern.items;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import com.avrgaming.civcraft.modern.CivCraftModernPlugin;

/** Original material whitelist, fractional defense levels and inclusive chance comparison. */
public final class CatalystEnhancements {
    private record Catalyst(boolean attack,Set<String> allowed,int chance,double amount) {}
    public record Result(ItemStack item,boolean success,double level) {}
    private final CivCraftModernPlugin plugin;
    private final NativeLegacyItems items;
    private final Map<String,Catalyst> catalysts=new HashMap<>();
    private final int free,extra;
    private final double extraChance;
    public CatalystEnhancements(CivCraftModernPlugin plugin,NativeLegacyItems items) {
        this.plugin=plugin;this.items=items;
        var yaml=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/materials.yml"));
        for(var row:yaml.getMapList("materials"))if(row.get("components") instanceof List<?> components)for(Object raw:components)if(raw instanceof Map<?,?> component && "Catalyst".equals(component.get("name"))) {
            String enhancement=Objects.toString(component.get("enhancement"));
            if(!Set.of("LoreEnhancementAttack","LoreEnhancementDefense").contains(enhancement))throw new IllegalArgumentException("Unknown catalyst enhancement: "+enhancement);
            var allowed=new HashSet<String>();for(String id:Objects.toString(component.get("allowed_materials")).split(","))allowed.add(id.trim());
            double amount=Double.parseDouble(Objects.toString(component.get("amount")));int chance=Integer.parseInt(Objects.toString(component.get("chance")));
            if(!Double.isFinite(amount) || amount<=0 || chance<0 || chance>100)throw new IllegalArgumentException("Invalid catalyst rule");
            catalysts.put(Objects.toString(row.get("id")),new Catalyst(enhancement.equals("LoreEnhancementAttack"),Set.copyOf(allowed),chance,amount));
        }
        var civ=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/civ.yml"));
        free=civ.getInt("global.free_catalyst_amount",3);extra=civ.getInt("global.extra_catalyst_amount",3);extraChance=civ.getDouble("global.extra_catalyst_percent",0);
        if(!Double.isFinite(extraChance) || extraChance<0 || extraChance>1)throw new IllegalArgumentException("Invalid extra catalyst chance");
    }
    public boolean catalyst(String id){return id!=null && catalysts.containsKey(id);}
    public static double level(ItemStack item,boolean attack) {
        if(item==null || !item.hasItemMeta())return 0;
        double value=item.getItemMeta().getPersistentDataContainer().getOrDefault(new NamespacedKey("civcraft",attack?"forge-attack":"forge-defense"),PersistentDataType.DOUBLE,0.0);
        return Double.isFinite(value) && value>0?value:0;
    }
    public Result forge(String catalystId,ItemStack original) {
        var catalyst=catalysts.get(catalystId);String id=items.id(original);
        if(catalyst==null || id==null || !catalyst.allowed().contains(id) || original.getAmount()!=1)throw new IllegalArgumentException("Этот катализатор не подходит к предмету в руке.");
        var definition=items.require(id);
        if(catalyst.attack()?definition.attack()<=0 && items.combat(id).rangedAttack()<=0:definition.defense()<=0)throw new IllegalArgumentException("Нельзя усилить этот предмет.");
        double level=level(original,catalyst.attack())+catalyst.amount();
        // The legacy component casts fractional defense level to int before checking thresholds.
        int checked=(int)level,roll=ThreadLocalRandom.current().nextInt(100);
        boolean success=checked<=free || roll-(checked<=extra?(int)(extraChance*100):0)<=catalyst.chance();
        if(!success)return new Result(null,false,level);
        ItemStack result=original.clone();var meta=result.getItemMeta();
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin,catalyst.attack()?"forge-attack":"forge-defense"),PersistentDataType.DOUBLE,level);
        var plain=PlainTextComponentSerializer.plainText();String base=meta.hasDisplayName()?plain.serialize(meta.displayName()).replaceFirst("\\s*\\(\\+[0-9.]+\\)$",""):id;
        meta.displayName(Component.text(base+" (+"+level+")"));
        String prefix=catalyst.attack()?"Усиление атаки: ":"Усиление защиты: ";
        var lore=new ArrayList<Component>();if(meta.lore()!=null)for(var line:meta.lore())if(!plain.serialize(line).startsWith(prefix))lore.add(line);
        lore.add(Component.text(prefix+"+"+level));meta.lore(lore);result.setItemMeta(meta);return new Result(result,true,level);
    }
}
