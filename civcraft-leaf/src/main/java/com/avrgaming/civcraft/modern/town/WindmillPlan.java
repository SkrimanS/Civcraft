package com.avrgaming.civcraft.modern.town;

import java.io.File;
import java.util.*;
import java.util.random.RandomGenerator;
import org.bukkit.configuration.file.YamlConfiguration;

/** Original windmill chooses distinct empty beds, then randomly chooses available seed types. */
public final class WindmillPlan {
    public enum Crop {
        WHEAT("WHEAT_SEEDS","WHEAT"),CARROTS("CARROT","CARROTS"),POTATOES("POTATO","POTATOES");
        private final String seed,block;
        Crop(String seed,String block){this.seed=seed;this.block=block;}
        public String seed(){return seed;}
        public String block(){return block;}
        public static Optional<Crop> seed(String material){return Arrays.stream(values()).filter(c->c.seed.equals(material)).findFirst();}
    }
    public record Spot(long farm,String world,int x,int y,int z) {
        public Spot{if(farm<1 || world==null || world.isBlank())throw new IllegalArgumentException("Invalid planting bed");}
    }
    private record Position(String world,int x,int y,int z) {}
    public record Plant(Spot spot,Crop crop) {}
    public record Result(List<Plant> plants,Map<Crop,Long> remaining) {
        public Result{plants=List.copyOf(plants);remaining=Map.copyOf(remaining);}
    }
    private final int maximum;
    public WindmillPlan(File file){this(YamlConfiguration.loadConfiguration(file).getInt("windmill.plant_max"));}
    public WindmillPlan(int maximum){if(maximum<0)throw new IllegalArgumentException("Invalid windmill capacity");this.maximum=maximum;}
    public int capacity(boolean machinery){return machinery?Math.multiplyExact(maximum,2):maximum;}
    public Result plan(Collection<Spot> candidates,Map<Crop,Long> seeds,boolean machinery,boolean war,RandomGenerator random) {
        var remaining=new EnumMap<Crop,Long>(Crop.class);long total=0;
        for(var crop:Crop.values()){long count=seeds.getOrDefault(crop,0L);if(count<0)throw new IllegalArgumentException("Negative windmill seed stock");remaining.put(crop,count);total=Math.addExact(total,count);}
        if(war || total==0 || capacity(machinery)==0)return new Result(List.of(),remaining);
        var unique=new LinkedHashMap<Position,Spot>();for(var spot:candidates)unique.putIfAbsent(new Position(spot.world(),spot.x(),spot.y(),spot.z()),spot);
        var available=new ArrayList<>(unique.values());int count=(int)Math.min(Math.min((long)available.size(),total),capacity(machinery));
        var selected=new ArrayList<Spot>();for(int i=0;i<count;i++)selected.add(available.remove(random.nextInt(available.size())));
        var plants=new ArrayList<Plant>();
        for(var spot:selected) {
            Crop crop=Crop.values()[random.nextInt(3)];
            // Equivalent to the old switch fall-through and its WHEAT/CARROTS/POTATOES fallback.
            if(remaining.get(crop)==0)crop=Arrays.stream(Crop.values()).filter(c->remaining.get(c)>0).findFirst().orElseThrow();
            remaining.put(crop,remaining.get(crop)-1);plants.add(new Plant(spot,crop));
        }
        return new Result(plants,remaining);
    }
}
