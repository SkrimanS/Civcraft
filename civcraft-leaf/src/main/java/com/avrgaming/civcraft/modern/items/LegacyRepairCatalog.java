package com.avrgaming.civcraft.modern.items;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Original fixed RepairCost override, otherwise round(base ^ (tier ^ damping)). */
public final class LegacyRepairCatalog {
    private final Map<String,Long> prices=new HashMap<>();
    public LegacyRepairCatalog(File materials,File structures) {
        var settings=YamlConfiguration.loadConfiguration(structures);
        double base=settings.getDouble("barracks.base_tier_repair",2000),damping=settings.getDouble("barracks.tier_damp",.175);
        if(!Double.isFinite(base) || !Double.isFinite(damping) || base<0 || damping<0)throw new IllegalArgumentException("Неверные настройки ремонта");
        for(var row:YamlConfiguration.loadConfiguration(materials).getMapList("materials")) {
            String category=Objects.toString(row.get("category"),"").toLowerCase(Locale.ROOT);int tier=0;
            var match=java.util.regex.Pattern.compile("(?:tier\\s*|t)([1-4])").matcher(category);if(match.find())tier=Integer.parseInt(match.group(1));
            double price=Math.pow(base,Math.pow(tier,damping));
            if(row.get("components") instanceof List<?> components)for(Object raw:components)if(raw instanceof Map<?,?> c && "RepairCost".equals(c.get("name")))price=Double.parseDouble(Objects.toString(c.get("value")));
            if(!Double.isFinite(price) || price<0 || price>1_000_000_000_000L)throw new IllegalArgumentException("Неверная цена ремонта: "+row.get("id"));
            prices.put(Objects.toString(row.get("id")),Math.round(price));
        }
    }
    public long price(String id){var price=prices.get(id);if(price==null)throw new IllegalArgumentException("Этот предмет нельзя отремонтировать в казарме.");return price;}
}
