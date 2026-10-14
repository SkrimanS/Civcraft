package com.avrgaming.civcraft.modern.trade;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradeShipLootTest {
    final TradeShipLoot.Chances chances=new TradeShipCatalog(new File("src/main/resources/civcraft/data/structures.yml")).chances();
    static class Fixed extends Random {
        private final int[] values; private int position;
        Fixed(int... values){this.values=values;}
        @Override public int nextInt(int bound){int value=values[position++];assertTrue(value>=0 && value<bound);return value;}
    }
    @Test void originalThresholdsAreCumulativeAndStrict() {
        assertEquals(new TradeShipLoot.Chances(.05,.01,.005,.001),chances);
        int[] rolls={9,10,49,50,99,100,499,500};
        String[] expected={"EMERALD","DIAMOND","DIAMOND","GOLD_INGOT","GOLD_INGOT","IRON_INGOT","IRON_INGOT",null};
        for(int i=0;i<rolls.length;i++) {
            var result=TradeShipLoot.roll("mat_crafted_reeds",1,chances,new Fixed(rolls[i],0,0,0));
            if(expected[i]==null) assertTrue(result.isEmpty());
            else {assertEquals(expected[i],result.getFirst().item());assertFalse(result.getFirst().custom());}
        }
    }
    @Test void ordinaryCargoRollsEachItemAndKeepsPowderAndFragmentCounts() {
        var loot=TradeShipLoot.roll("mat_crafted_reeds",2,chances,new Fixed(10,2,2,0,9,3));
        assertEquals(List.of(new TradeShipLoot.Reward("GUNPOWDER",2,false),
                new TradeShipLoot.Reward("mat_metallic_crystal_fragment_3",3,true),
                new TradeShipLoot.Reward("mat_ionic_crystal_fragment_3",1,true),
                new TradeShipLoot.Reward("GUNPOWDER",3,false)),loot);
    }
    @Test void eggsStopAfterFirstRewardAndPreserveTierAndEquipment() {
        var huge=TradeShipLoot.roll("mat_creeper_egg_4",64,chances,new Fixed(500,0));
        assertEquals(6,huge.size());assertEquals(new TradeShipLoot.Reward("mat_metallic_crystal_4",2,true),huge.getFirst());
        assertEquals("mat_tungsten_chestplate",huge.get(2).item());
        var animal=TradeShipLoot.roll("mat_cow_egg_2",64,chances,new Fixed(10));
        assertEquals("mat_recurve_bow",animal.get(2).item());
        var zombie=TradeShipLoot.roll("mat_zombie_egg_3",64,chances,new Fixed(0));
        assertEquals(2,zombie.size());
        assertEquals("mat_metallic_crystal_fragment_1",TradeShipLoot.roll("mat_sheep_egg",1,chances,new Fixed(100)).getFirst().item());
    }
    @Test void allConfiguredCustomRewardIdsExistInOriginalMaterialCatalog() {
        Set<String> materials=new HashSet<>();
        for(var material:YamlConfiguration.loadConfiguration(new File("src/main/resources/civcraft/data/materials.yml")).getMapList("materials"))
            materials.add(String.valueOf(material.get("id")));
        for(String mob:List.of("creeper","skeleton","spider","zombie","slime","enderman","pig","cow","chicken","sheep"))
            for(int tier=1;tier<=4;tier++)
                for(int roll:new int[]{0,10,50,100})
                    for(var reward:TradeShipLoot.roll("mat_"+mob+"_egg_"+tier,1,chances,new Fixed(roll)))
                        assertTrue(materials.contains(reward.item()),reward.item());
    }
}
