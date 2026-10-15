package com.avrgaming.civcraft.modern.town;

import java.io.File;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrommelLootTest {
    final TrommelLoot loot=new TrommelLoot(new File("src/main/resources/civcraft/data/structures.yml"));
    @Test void fourInputLevelsExcludeGravelOrdinaryStoneAndPolishedVariants() {
        for(int level=1;level<=4;level++) {
            assertTrue(TrommelLoot.accepts("COBBLESTONE",level));assertEquals(level>=2,TrommelLoot.accepts("GRANITE",level));assertEquals(level>=3,TrommelLoot.accepts("DIORITE",level));assertEquals(level>=4,TrommelLoot.accepts("ANDESITE",level));
            for(String invalid:List.of("GRAVEL","STONE","POLISHED_GRANITE","DIRT","DEEPSLATE"))assertFalse(TrommelLoot.accepts(invalid,level));
        }
    }
    Map<String,Integer> counts(String material,double extraction) {
        var result=new HashMap<String,Integer>();for(int roll=0;roll<loot.maximum(material);roll++)result.merge(loot.select(material,extraction,roll),1,Integer::sum);return result;
    }
    @Test void cobblestoneUsesTheGravelTableWithOriginalNestedProbabilities() {
        assertEquals(Map.of("mat_chromium_ore",8,"EMERALD",12,"DIAMOND",30,"GOLD_INGOT",150,"REDSTONE",200,"IRON_INGOT",200,"GRAVEL",9400),counts("COBBLESTONE",0));
        assertEquals(Map.of("mat_chromium_ore",12,"EMERALD",18,"DIAMOND",45,"GOLD_INGOT",225,"REDSTONE",300,"IRON_INGOT",300,"GRAVEL",9100),counts("COBBLESTONE",.5));
    }
    @Test void graniteOrderPreservesShadowedRefinedTungstenAndByproducts() {
        var counts=counts("GRANITE",0);assertEquals(2,counts.get("CRYSTAL"));assertEquals(6,counts.get("mat_forged_chromium"));assertFalse(counts.containsKey("mat_forged_tungsten"));assertEquals(12,counts.get("FRAGMENT"));
        assertEquals(250,counts.get("mat_tungsten_ore"));assertEquals(4000,counts.get("POLISHED_GRANITE"));assertEquals(65000,counts.get("DIRT"));assertEquals(25000,counts.get("GRAVEL"));
        assertFalse(counts("DIORITE",0).containsKey("mat_tungsten_ore"));assertTrue(counts("ANDESITE",0).containsKey("mat_tungsten_ore"));
        assertFalse(counts("GRANITE",.5).containsKey("GRAVEL"));
    }
    @Test void crystalNestedRollsRetainBothTiersBothTypesAndFragments() {
        for(boolean fragment:List.of(false,true)) {
            String prefix="mat_ionic_crystal_"+(fragment?"fragment_":""),metal="mat_metallic_crystal_"+(fragment?"fragment_":"");
            assertEquals(prefix+2,TrommelLoot.crystal(fragment,100000,9999,49999));assertEquals(metal+2,TrommelLoot.crystal(fragment,100000,9999,50000));
            assertEquals(prefix+1,TrommelLoot.crystal(fragment,100000,10000,99999));assertEquals(metal+1,TrommelLoot.crystal(fragment,100000,50000,0));
        }
        assertTrue(loot.items().containsAll(List.of("mat_forged_chromium","mat_forged_tungsten","mat_ionic_crystal_2","mat_metallic_crystal_fragment_2")));
        assertThrows(IllegalArgumentException.class,()->loot.select("COBBLESTONE",Double.NaN,0));assertThrows(IllegalArgumentException.class,()->loot.select("COBBLESTONE",0,10000));
    }
}
