package com.avrgaming.civcraft.modern.camp;

import com.avrgaming.civcraft.modern.town.ConsumptionContainers;
import java.util.*;
import java.util.random.RandomGenerator;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Ordered, independent 0..999 rolls from the original SifterComponent. */
public record CampSifterRules(double goldChance,double ironChance) {
    public CampSifterRules {
        if(!Double.isFinite(goldChance) || !Double.isFinite(ironChance) || goldChance<0 || goldChance>1 || ironChance<0 || ironChance>1)
            throw new IllegalArgumentException("Invalid camp sifter probabilities");
    }
    public Material roll(RandomGenerator random) {
        if(random.nextInt(1000)<goldChance*1000)return Material.GOLD_NUGGET;
        if(random.nextInt(1000)<ironChance*1000)return Material.IRON_INGOT;
        return Material.GRAVEL;
    }
    public Optional<List<ConsumptionContainers.Image>> plan(List<ConsumptionContainers.Image> source,List<ConsumptionContainers.Image> destination,RandomGenerator random) {
        if(source.isEmpty() || destination.isEmpty())return Optional.empty();
        var occupied=new HashSet<ConsumptionContainers.Position>();
        for(var image:source)if(!occupied.add(image.position()))throw new IllegalArgumentException("Duplicate sifter input");
        for(var image:destination)if(!occupied.add(image.position()))throw new IllegalArgumentException("Sifter input and output overlap");
        // Original output guard requires an empty slot even when an existing stack could merge.
        boolean empty=destination.stream().flatMap(i->Arrays.stream(i.contents())).anyMatch(s->s==null || s.getType().isAir());
        if(!empty)return Optional.empty();
        var input=new ArrayList<ConsumptionContainers.Image>();boolean removed=false;
        for(var image:source) {
            var slots=image.contents();
            for(int i=0;i<slots.length && !removed;i++)if(slots[i]!=null && slots[i].getType()==Material.COBBLESTONE) {
                if(slots[i].getAmount()==1)slots[i]=null;else slots[i].setAmount(slots[i].getAmount()-1);
                removed=true;
            }
            input.add(new ConsumptionContainers.Image(image.position(),slots));
        }
        if(!removed)return Optional.empty();
        input.addAll(ConsumptionContainers.addBestEffort(destination,new ItemStack(roll(random))));
        return Optional.of(List.copyOf(input));
    }
}
