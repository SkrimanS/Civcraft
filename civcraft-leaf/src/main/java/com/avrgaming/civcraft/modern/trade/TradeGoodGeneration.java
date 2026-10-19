package com.avrgaming.civcraft.modern.trade;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

public final class TradeGoodGeneration {
    public record Settings(int minimum,int maximum,int radiusX,int radiusZ,int seed) {
        public Settings {
            if(minimum<1||maximum<minimum||radiusX<1||radiusZ<1)
                throw new IllegalArgumentException("Неверные настройки генерации торговых товаров");
        }
    }
    public record Hemisphere(int minX,int maxX,int minZ,int maxZ) {
        public boolean contains(int x,int z) {
            return (minX==0||x>=minX)&&(maxX==0||x<=maxX)&&(minZ==0||z>=minZ)&&(maxZ==0||z<=maxZ);
        }
    }
    public record Chunk(int x,int z) {}
    public record Pick(Chunk chunk,String land,String water) {}
    private final Settings settings;
    private final Map<String,Hemisphere> hemispheres;
    private final TradeGoodsCatalog catalog;
    public TradeGoodGeneration(Settings settings,Map<String,Hemisphere> hemispheres,TradeGoodsCatalog catalog) {
        this.settings=settings;this.hemispheres=Map.copyOf(hemispheres);this.catalog=catalog;
    }
    public static TradeGoodGeneration load(File file,TradeGoodsCatalog catalog) {
        var yaml=YamlConfiguration.loadConfiguration(file);var regions=new HashMap<String,Hemisphere>();
        for(var raw:yaml.getMapList("hemispheres"))regions.put(String.valueOf(raw.get("id")),new Hemisphere(
                ((Number)raw.get("x_min")).intValue(),((Number)raw.get("x_max")).intValue(),
                ((Number)raw.get("z_min")).intValue(),((Number)raw.get("z_max")).intValue()));
        // Bukkit's original getInteger uses a signed 32-bit seed, including overflow of the YAML long.
        return new TradeGoodGeneration(new Settings(yaml.getInt("generation.chunks_min"),yaml.getInt("generation.chunks_max"),
                yaml.getInt("generation.chunks_x"),yaml.getInt("generation.chunks_z"),yaml.getInt("generation.seed")),regions,catalog);
    }
    public Settings settings(){return settings;}
    public List<TradeGoodsCatalog.Good> eligible(boolean water,int x,int z) {
        return catalog.list().stream().filter(g->g.water()==water).filter(g->g.hemispheres().isEmpty()||g.hemispheres().stream()
                .map(hemispheres::get).filter(Objects::nonNull).anyMatch(h->h.contains(x,z))).toList();
    }
    public static String pick(List<TradeGoodsCatalog.Good> goods,int roll,Random random) {
        if(roll<0||roll>=100)throw new IllegalArgumentException("Неверный результат выбора редкости торгового товара");
        double lowest=goods.stream().filter(g->roll<g.rarity()*100).mapToDouble(TradeGoodsCatalog.Good::rarity).min().orElse(Double.NaN);
        var matches=goods.stream().filter(g->g.rarity()==lowest).toList();
        return matches.isEmpty()?null:matches.get(random.nextInt(matches.size())).id();
    }
    public Map<Chunk,Pick> plan() {
        var coords=new Random(settings.seed());
        // Keep the coordinate RNG independent, as in the source; persist all type choices in SQL.
        var choices=new Random(((long)settings.seed())^0x4349564352414654L);
        var result=new LinkedHashMap<Chunk,Pick>();int difference=settings.maximum()-settings.minimum();
        for(long x=-(long)settings.radiusX();x<settings.radiusX();x+=settings.minimum())
            for(long z=-(long)settings.radiusZ();z<settings.radiusZ();z+=settings.minimum()) {
                int cx=(int)x,cz=(int)z;
                if(difference>0) {
                    boolean plus=coords.nextBoolean();int delta=coords.nextInt(difference);cx=Math.addExact(cx,plus?delta:-delta);
                    plus=coords.nextBoolean();delta=coords.nextInt(difference);cz=Math.addExact(cz,plus?delta:-delta);
                }
                var land=eligible(false,cx,cz);var water=eligible(true,cx,cz);int roll=choices.nextInt(100);
                String landPick=pick(land,roll,choices),waterPick=pick(water,roll,choices);
                if(landPick==null||waterPick==null)continue;
                boolean skip=false;
                for(int dx=-4;dx<4 && !skip;dx++)for(int dz=-4;dz<4 && !skip;dz++) {
                    var neighbor=result.get(new Chunk(cx+dx,cz+dz));if(neighbor==null)continue;
                    if(neighbor.land().equals(landPick))landPick=different(land,neighbor.land(),choices);
                    if(neighbor.water().equals(waterPick))waterPick=different(water,neighbor.water(),choices);
                    skip=landPick==null||waterPick==null;
                }
                if(!skip){var chunk=new Chunk(cx,cz);result.put(chunk,new Pick(chunk,landPick,waterPick));}
            }
        return Collections.unmodifiableMap(result);
    }
    private static String different(List<TradeGoodsCatalog.Good> goods,String previous,Random random) {
        if(goods.size()<=1)return null;
        // A broken rarity configuration must not hang server startup in an unbounded retry loop.
        for(int attempt=0;attempt<1000;attempt++){String next=pick(goods,random.nextInt(100),random);if(next!=null&&!next.equals(previous))return next;}
        return null;
    }
}
