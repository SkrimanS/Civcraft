package com.avrgaming.civcraft.modern.trade;

import java.io.File;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradeGoodGenerationTest {
    final File file=new File("src/main/resources/civcraft/data/goods.yml");
    final TradeGoodsCatalog catalog=new TradeGoodsCatalog(file);
    @Test void zeroBoundsAreOpenAndOriginalHemisphereCoordinatesAreChunks() {
        var north=new TradeGoodGeneration.Hemisphere(0,0,0,-200);
        assertTrue(north.contains(10000,-200));assertFalse(north.contains(-10000,-199));
        var generator=TradeGoodGeneration.load(file,catalog);
        assertEquals((int)2520150148L,generator.settings().seed());
        assertTrue(generator.eligible(false,-201,-201).stream().anyMatch(g->g.id().equals("good_coffee")));
        assertFalse(generator.eligible(false,201,201).stream().anyMatch(g->g.id().equals("good_coffee")));
    }
    @Test void rarityChoosesLowestQualifyingTierAndPreservesEqualRarityGoods() {
        var rare=new TradeGoodsCatalog.Good("rare","Редкий",1,false,1,0,List.of(),List.of(),.25);
        var common=new TradeGoodsCatalog.Good("common","Обычный",1,false,1,0,List.of(),List.of(),1);
        assertEquals("rare",TradeGoodGeneration.pick(List.of(rare,common),24,new Random(1)));
        assertEquals("common",TradeGoodGeneration.pick(List.of(rare,common),25,new Random(1)));
        Set<String> selected=new HashSet<>();var random=new Random(3);
        for(int i=0;i<100;i++)selected.add(TradeGoodGeneration.pick(catalog.list().stream().filter(g->g.water()).toList(),99,random));
        assertEquals(6,selected.size());
    }
    @Test void gridAndJitterStayReproducibleWithoutForcingWorldGeneration() {
        var grid=new TradeGoodGeneration(new TradeGoodGeneration.Settings(15,15,30,30,1),Map.of(),catalog).plan();
        assertEquals(16,grid.size());
        for(var c:grid.keySet()){assertTrue(Set.of(-30,-15,0,15).contains(c.x()));assertTrue(Set.of(-30,-15,0,15).contains(c.z()));}
        var generator=TradeGoodGeneration.load(file,catalog);var first=generator.plan();
        assertTrue(first.size()>6000);assertEquals(first,generator.plan());
        for(var p:first.values()){assertFalse(catalog.require(p.land()).water());assertTrue(catalog.require(p.water()).water());}
    }
}
