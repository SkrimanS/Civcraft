package com.avrgaming.civcraft.modern.trade;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

public final class TradeGoodsCatalog {
    public record Good(String id,String name,double value,boolean water,int material,int data,
                       List<String> buffs,List<String> hemispheres,double rarity) {
        public Good {buffs=List.copyOf(buffs);hemispheres=List.copyOf(hemispheres);}
    }
    private final Map<String,Good> goods;
    public TradeGoodsCatalog(File file) {
        var yaml=YamlConfiguration.loadConfiguration(file);var loaded=new LinkedHashMap<String,Good>();
        for(String section:List.of("land_goods","water_goods"))for(var raw:yaml.getMapList(section)) {
            String id=Objects.toString(raw.get("id"),"");
            double value=((Number)raw.get("value")).doubleValue();
            double rarity=raw.get("rarity") instanceof Number number?number.doubleValue():1;
            if(id.isBlank()||!Double.isFinite(value)||value<0||!Double.isFinite(rarity)||rarity<0)
                throw new IllegalArgumentException("Неверный торговый товар: "+id);
            var good=new Good(id,Objects.toString(raw.get("name"),id),value,section.equals("water_goods"),
                    ((Number)raw.get("material")).intValue(),((Number)raw.get("material_data")).intValue(),
                    split(raw.get("buffs")),split(raw.get("hemispheres")),rarity);
            if(loaded.putIfAbsent(id,good)!=null)throw new IllegalArgumentException("Повторный торговый товар: "+id);
        }
        if(loaded.isEmpty())throw new IllegalArgumentException("Торговые товары не настроены");
        goods=Collections.unmodifiableMap(loaded);
    }
    private static List<String> split(Object text) {
        return Arrays.stream(Objects.toString(text,"").split(",")).map(String::trim).filter(s->!s.isEmpty()).toList();
    }
    public Good require(String id) {
        var result=goods.get(id);if(result==null)throw new IllegalArgumentException("Неизвестный торговый товар: "+id);return result;
    }
    public List<Good> list(){return List.copyOf(goods.values());}
}
