package com.avrgaming.civcraft.modern.trade;

import com.avrgaming.civcraft.modern.items.NativeLegacyItems;
import java.io.File;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

public final class MarketCatalog {
    public record Entry(int id,String name,String ident,String customId,ItemStack exemplar,long base,int step,boolean stackable) {}
    private final Map<Integer,Entry> entries=new LinkedHashMap<>();
    private final int stepCount;
    private final double rate;
    public MarketCatalog(File file,NativeLegacyItems items) {
        var yaml=YamlConfiguration.loadConfiguration(file);
        stepCount=yaml.getInt("step_count",512);rate=yaml.getDouble("rate",0.15);
        int defaultStep=yaml.getInt("step",1);
        if(stepCount<1 || defaultStep<1 || !Double.isFinite(rate) || rate<0)throw new IllegalArgumentException("Invalid market pricing settings");
        for(var raw:yaml.getMapList("items")) {
            int id=((Number)raw.get("id")).intValue(),type=((Number)raw.get("type_id")).intValue(),data=((Number)raw.get("data")).intValue();
            String custom=raw.get("custom_id")==null?null:raw.get("custom_id").toString();
            ItemStack stack=custom==null?items.vanilla(type,data,1):items.create(custom,1);
            long base=((Number)raw.get("value")).longValue();
            int step=raw.get("step")==null?defaultStep:((Number)raw.get("step")).intValue();
            if(base<1 || base>1_000_000_000 || step<1)throw new IllegalArgumentException("Invalid market price for "+id);
            entries.put(id,new Entry(id,raw.get("name").toString(),custom==null?type+":"+data:custom,custom,stack,base,step,!Boolean.FALSE.equals(raw.get("stackable"))));
        }
    }
    public Entry require(int id){var entry=entries.get(id);if(entry==null)throw new IllegalArgumentException("Неизвестный товар: "+id);return entry;}
    public Optional<Entry> find(int id){return Optional.ofNullable(entries.get(id));}
    public int stepCount(){return stepCount;}
    public double rate(){return rate;}
    public List<Entry> list(){return List.copyOf(entries.values());}
}
