package com.avrgaming.civcraft.modern.war;
import java.util.Set;
import java.util.function.IntSupplier;
/** Branch order and inclusive rolls from Buildable.fancyDestroyStructureBlocks. */
public final class RuinRules {
    private RuinRules() {}
    private static final Set<String> CRUMBLE=Set.of("BEDROCK","COAL_BLOCK","EMERALD_BLOCK","LAPIS_BLOCK","SPONGE","WET_SPONGE","HAY_BLOCK","GOLD_BLOCK","DIAMOND_BLOCK","IRON_BLOCK","REDSTONE_BLOCK","ENDER_CHEST","BEACON");
    public record Result(String material,boolean burst) {}
    public static Result choose(String material,IntSupplier roll) {
        if(CRUMBLE.contains(material))return new Result("GRAVEL",false);
        if(roll.getAsInt()<=70)return new Result("AIR",false);
        if(roll.getAsInt()<=30)return new Result("GRAVEL",false);
        if(roll.getAsInt()<=10)return new Result("FIRE",false);
        return new Result(material,roll.getAsInt()<=1);
    }
}
