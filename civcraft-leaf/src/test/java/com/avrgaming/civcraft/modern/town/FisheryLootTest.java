package com.avrgaming.civcraft.modern.town;

import java.io.File;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FisheryLootTest {
    final FisheryLoot loot=new FisheryLoot(new File("src/main/resources/civcraft/data/structures.yml"));
    @Test void originalNestedRatesAndLevelGatesHaveExactDistribution() {
        int[][] expected={{5000,5000,0,0,0},{5000,4500,500,0,0},{5000,4500,400,100,0},{5000,4500,400,90,10}};
        for(int level=1;level<=4;level++){int[] counts=new int[5];for(int roll=0;roll<10000;roll++)counts[loot.tier(level,roll)]++;assertArrayEquals(expected[level-1],counts);}
        assertThrows(IllegalArgumentException.class,()->loot.tier(5,0));assertThrows(IllegalArgumentException.class,()->loot.tier(1,10000));
    }
    @Test void biomePairsAndRareThresholdsMatchAllFourTiers() {
        String[][] pairs={{"brown_trout","gag_grouper","atlantic_striped_bass","arrowtooth_flounder","atlantic_cod","atlantic_surfclam"},{"brook_trout","red_grouper","pacific_ocean_perch","summer_flounder","pacific_cod","ocean_quahog"},{"cutthroat_trout","black_sea_bass","acadian_redfish","winter_flounder","lingcod","northern_quahog"},{"rainbow_trout","wreckfish","widow_rockfish","yellowtail_flounder","sablefish","geoduck"}};
        for(int tier=1;tier<=4;tier++)for(int group=1;group<=3;group++) {
            int boundary=tier<=2?90:80;
            assertEquals("mat_fish_"+pairs[tier-1][(group-1)*2],FisheryLoot.item(tier,group,boundary-1,0));
            assertEquals("mat_fish_"+pairs[tier-1][(group-1)*2+1],FisheryLoot.item(tier,group,boundary,0));
        }
        assertEquals("COD",FisheryLoot.item(1,0,0,0));assertEquals("mat_fish_fish",FisheryLoot.item(4,0,99,0));
        assertEquals(1,FisheryLoot.group("BIRCH_FOREST_HILLS"));assertEquals(2,FisheryLoot.group("EXTREME_HILLS"));assertEquals(3,FisheryLoot.group("PLAINS"));assertEquals(0,FisheryLoot.group("HELL"));
    }
    @Test void tierZeroStrictBoundariesPreserveJunkAndVanillaFishVariants() {
        assertEquals("mat_fish_pufferfish",FisheryLoot.item(0,3,95,0));assertEquals("mat_fish_clownfish",FisheryLoot.item(0,3,94,0));assertEquals("mat_fish_salmon",FisheryLoot.item(0,3,85,0));assertEquals("mat_fish_fish",FisheryLoot.item(0,3,75,0));
        String[] junk={"pond_scum","tangled_string","seaweed","tadpole","minnows"};int[] rolls={30,31,51,71,91};
        for(int i=0;i<rolls.length;i++)assertEquals("mat_"+junk[i],FisheryLoot.item(0,3,50,rolls[i]));
    }
    @Test void oneRodSupportsExactly64UsesRegardlessOfModernEnchantments() {
        int damage=0,uses=0;while(damage>=0){damage=FisheryLoot.damageAfterUse(damage);uses++;}assertEquals(64,uses);
        assertEquals(-1,FisheryLoot.damageAfterUse(63));assertThrows(IllegalArgumentException.class,()->FisheryLoot.damageAfterUse(-1));
    }
}
