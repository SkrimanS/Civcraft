package com.avrgaming.civcraft.modern.town;

import java.io.File;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuarryLootTest {
    final QuarryLoot loot=new QuarryLoot(new File("src/main/resources/civcraft/data/structures.yml"));
    @Test void originalToolGatesKeepGoldAtLevelOneAndExcludeModernNonLegacyTools() {
        for(int level=1;level<=4;level++) {
            assertTrue(QuarryLoot.accepts("WOODEN_PICKAXE",level));assertTrue(QuarryLoot.accepts("GOLDEN_PICKAXE",level));
            assertEquals(level>=2,QuarryLoot.accepts("STONE_PICKAXE",level));assertEquals(level>=3,QuarryLoot.accepts("IRON_PICKAXE",level));assertEquals(level>=4,QuarryLoot.accepts("DIAMOND_PICKAXE",level));
            assertFalse(QuarryLoot.accepts("NETHERITE_PICKAXE",level));assertFalse(QuarryLoot.accepts("IRON_AXE",level));
        }
    }
    Map<String,Integer> counts(String tool,double extraction,String government) {
        var counts=new HashMap<String,Integer>();for(int roll=0;roll<loot.maximum();roll++)counts.merge(loot.select(tool,extraction,government,roll),1,Integer::sum);return counts;
    }
    @Test void woodenGoldenStoneAndIronTablesRetainOrderedProbabilities() {
        assertEquals(Map.of("COAL",2500,"OTHER",2500,"COBBLESTONE",20000,"JUNK",75000),counts("WOODEN_PICKAXE",0,"gov_tribalism"));
        assertEquals(Map.of("COAL",2500,"OTHER",2500,"COBBLESTONE",95000),counts("GOLDEN_PICKAXE",0,"gov_tribalism"));
        assertEquals(Map.of("GOLD_INGOT",200,"IRON_INGOT",1800,"COAL",3000,"OTHER",5000,"COBBLESTONE",15000,"JUNK",75000),counts("STONE_PICKAXE",0,"gov_tribalism"));
        assertEquals(Map.of("RARE",40,"mat_tungsten_ore",50,"GOLD_INGOT",110,"REDSTONE",550,"IRON_INGOT",1250,"COAL",3000,"OTHER",5000,"COBBLESTONE",15000,"JUNK",75000),counts("IRON_PICKAXE",0,"gov_tribalism"));
        assertEquals(counts("IRON_PICKAXE",0,"gov_tribalism"),counts("DIAMOND_PICKAXE",0,"gov_tribalism"));
    }
    @Test void extractionAndGovernmentModifyThresholdsBeforeToolDivisors() {
        assertEquals(50000,counts("WOODEN_PICKAXE",0,"gov_despotism").get("JUNK"));
        assertEquals(80000,counts("WOODEN_PICKAXE",0,"gov_monarchy").get("JUNK"));
        assertEquals(counts("WOODEN_PICKAXE",0,"gov_monarchy"),counts("WOODEN_PICKAXE",0,"gov_theocracy"));
        assertEquals(25000,counts("WOODEN_PICKAXE",.5,"gov_despotism").get("JUNK"));
        assertEquals(70000,counts("WOODEN_PICKAXE",.5,"gov_monarchy").get("JUNK"));
        assertThrows(IllegalArgumentException.class,()->loot.select("WOODEN_PICKAXE",Double.NaN,"gov_tribalism",0));
    }
    @Test void secondaryStoneJunkAndRareSelectionsMatchLegacyBoundaries() {
        assertEquals("ANDESITE",loot.detail("OTHER",12499));assertEquals("DIORITE",loot.detail("OTHER",12500));assertEquals("GRANITE",loot.detail("OTHER",20000));
        assertEquals("EMERALD",loot.detail("RARE",19999));assertEquals("DIAMOND",loot.detail("RARE",20000));
        assertEquals("PODZOL",loot.detail("JUNK",1));assertEquals("COARSE_DIRT",loot.detail("JUNK",2));assertEquals("DIRT",loot.detail("JUNK",5));
    }
    @Test void durabilityAndEfficiencyPreserveFullLastCycleOutputAndLegacyLimits() {
        int[] limits={59,131,250,32,1561};String[] tools={"WOODEN_PICKAXE","STONE_PICKAXE","IRON_PICKAXE","GOLDEN_PICKAXE","DIAMOND_PICKAXE"};
        for(int i=0;i<tools.length;i++){assertEquals(limits[i]-1,QuarryLoot.damageAfterUse(tools[i],limits[i]-2,1,1));assertEquals(-1,QuarryLoot.damageAfterUse(tools[i],limits[i]-1,1,1));}
        assertEquals(6,QuarryLoot.modifier(5));assertEquals(-1,QuarryLoot.damageAfterUse("WOODEN_PICKAXE",58,6,1));assertEquals(-1,QuarryLoot.damageAfterUse("WOODEN_PICKAXE",0,1,2));
        Random red=new Random(){int call;@Override public int nextInt(int bound){return call++==0?200:4;}};
        assertEquals(new QuarryLoot.Reward("REDSTONE",30),loot.roll("IRON_PICKAXE",5,0,"gov_tribalism",red));
        Random coal=new Random(){@Override public int nextInt(int bound){return 0;}};
        assertEquals(new QuarryLoot.Reward("COAL",6),loot.roll("WOODEN_PICKAXE",5,0,"gov_tribalism",coal));
    }
}
