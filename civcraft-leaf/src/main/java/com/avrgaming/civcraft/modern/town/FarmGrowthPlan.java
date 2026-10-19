package com.avrgaming.civcraft.modern.town;

import java.util.*;
import java.util.random.RandomGenerator;

/** Snapshot-based rules from the original FarmChunk; light and hydration are deliberately ignored. */
public final class FarmGrowthPlan {
    public enum Crop {
        WHEAT(7), CARROTS(7), POTATOES(7), NETHER_WART(3), COCOA(2), MELON_STEM(7), PUMPKIN_STEM(7);
        private final int maximum;
        Crop(int maximum){this.maximum=maximum;}
        public int maximum(){return maximum;}
        public boolean stem(){return this==MELON_STEM || this==PUMPKIN_STEM;}
        public String fruit(){if(!stem())throw new IllegalStateException("Not a stem");return this==MELON_STEM?"MELON":"PUMPKIN";}
    }
    public record Position(String world,int x,int y,int z) {
        public Position{if(world==null || world.isBlank())throw new IllegalArgumentException("Не найден мир фермы");}
        Position offset(int dx,int dz){return new Position(world,x+dx,y,z+dz);}
    }
    /** Neighbor order: west, east, north, south. null means outside the snapshot chunk. */
    public record Plant(Position position,Crop crop,int age,List<String> neighbors) {
        public Plant{
            Objects.requireNonNull(position);Objects.requireNonNull(crop);
            if(age<0 || age>crop.maximum())throw new IllegalArgumentException("Неверный возраст растения");
            neighbors=Collections.unmodifiableList(new ArrayList<>(neighbors));
            if(crop.stem() && neighbors.size()!=4)throw new IllegalArgumentException("Стеблю нужны четыре соседних блока");
        }
        public boolean eligible(){
            if(!crop.stem())return age<crop.maximum();
            // Even an immature stem is excluded if a neighbor is outside the chunk or has its fruit.
            return !neighbors.contains(null) && !neighbors.contains(crop.fruit()) && neighbors.stream().anyMatch(FarmGrowthPlan::air);
        }
    }
    public record Change(Position position,String material,int age,boolean spawn) {}
    public record Budget(double effectiveRate,int guaranteed,int fractionalChance) {}
    private final int growsPerTick;
    public FarmGrowthPlan(int growsPerTick){if(growsPerTick<0)throw new IllegalArgumentException("Неверный лимит роста");this.growsPerTick=growsPerTick;}
    public Budget budget(double townGrowth,double biomeMultiplier){
        if(!Double.isFinite(townGrowth) || townGrowth<0 || !Double.isFinite(biomeMultiplier) || biomeMultiplier<0)throw new IllegalArgumentException("Неверные параметры роста фермы");
        double rate=townGrowth/100*biomeMultiplier;
        double count=rate*growsPerTick;
        if(!Double.isFinite(count) || count>Integer.MAX_VALUE-1)throw new IllegalArgumentException("Переполнение лимита роста");
        // Original remainder uses town growth, not the biome-adjusted rate or grows_per_tick.
        return new Budget(rate,(int)count,(int)(townGrowth%100));
    }
    public List<Change> plan(Collection<Plant> snapshot,double townGrowth,double biomeMultiplier,boolean active,RandomGenerator random){
        var budget=budget(townGrowth,biomeMultiplier);
        if(!active)return List.of();
        var candidates=snapshot.stream().filter(Plant::eligible).toList();
        if(candidates.isEmpty())return List.of();
        var changes=new LinkedHashMap<Position,Change>();
        for(int i=0;i<budget.guaranteed();i++)grow(candidates.get(random.nextInt(candidates.size())),random,changes);
        if(budget.fractionalChance()!=0 && random.nextInt(100)<budget.fractionalChance())grow(candidates.get(random.nextInt(candidates.size())),random,changes);
        return List.copyOf(changes.values());
    }
    private static void grow(Plant plant,RandomGenerator random,Map<Position,Change> changes){
        var p=plant.position();var crop=plant.crop();
        if(plant.age()<crop.maximum()){
            if(!spawnArea(p))changes.put(p,new Change(p,crop.name(),plant.age()+1,false));
        }else if(crop.stem() && random.nextInt(10)>7){
            int direction=random.nextInt(4);
            if(!air(plant.neighbors().get(direction)))return;
            int[][] offsets={{-1,0},{1,0},{0,-1},{0,1}};
            var target=p.offset(offsets[direction][0],offsets[direction][1]);
            if(!spawnArea(target))changes.put(target,new Change(target,crop.fruit(),0,true));
        }
    }
    private static boolean spawnArea(Position p){return p.x()>-64 && p.x()<64 && p.z()>-64 && p.z()<64;}
    private static boolean air(String name){return "AIR".equals(name) || "CAVE_AIR".equals(name) || "VOID_AIR".equals(name);}
}
