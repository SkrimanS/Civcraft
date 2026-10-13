package com.avrgaming.civcraft.modern.culture;
import java.io.File;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CultureRegionsTest {
    CultureLevels levels(){return new CultureLevels(YamlConfiguration.loadConfiguration(new File("src/main/resources/civcraft/data/culture.yml")).getMapList("culture_levels"));}
    CultureRegions.Cell cell(int x,int z){return new CultureRegions.Cell("world",x,z);}
    @Test void originalThresholdsStartAtOneAndAdvanceAtFiveHundred() {
        var levels=levels();assertEquals(1,levels.forCulture(0).number());assertEquals(8,levels.forCulture(499).radius());
        assertEquals(2,levels.forCulture(500).number());assertEquals(10,levels.forCulture(500).radius());
        assertEquals(10,levels.forCulture(Long.MAX_VALUE).number());
    }
    @Test void initialDiamondExcludesExactRadiusAndRetainsCenter() {
        var town=new CultureRegions.Town(1,cell(0,0),0,levels().forCulture(0));
        var result=CultureRegions.calculate(List.of(town),Map.of(),Map.of());
        assertEquals(113,result.size());assertEquals(1L,result.get(cell(0,0)));
        assertTrue(result.containsKey(cell(7,0)));assertFalse(result.containsKey(cell(8,0)));assertFalse(result.containsKey(cell(4,4)));
    }
    @Test void higherLevelExpandsAndADecreasedLevelTrimsOldTerritory() {
        var first=new CultureRegions.Town(1,cell(-10,-10),0,levels().forCulture(0));
        var second=new CultureRegions.Town(1,cell(-10,-10),500,levels().forCulture(500));
        var small=CultureRegions.calculate(List.of(first),Map.of(),Map.of());
        var large=CultureRegions.calculate(List.of(second),Map.of(),small);assertEquals(181,large.size());
        assertEquals(small,CultureRegions.calculate(List.of(first),Map.of(),large));
    }
    @Test void neverClaimsForeignCityPlotsAndKeepsWorldsIndependent() {
        var first=new CultureRegions.Town(1,cell(0,0),0,levels().forCulture(0));
        var second=new CultureRegions.Town(2,new CultureRegions.Cell("another",0,0),0,levels().forCulture(0));
        var result=CultureRegions.calculate(List.of(first,second),Map.of(cell(1,0),3L),Map.of(cell(99,99),99L));
        assertFalse(result.containsKey(cell(1,0)));assertFalse(result.containsKey(cell(99,99)));
        assertEquals(2L,result.get(new CultureRegions.Cell("another",0,0)));
    }
    @Test void competingCitiesPreserveProtectedPlotsAndHaveOneOwnerPerCell() {
        var first=new CultureRegions.Town(1,cell(0,0),0,levels().forCulture(0));
        var second=new CultureRegions.Town(2,cell(8,0),500,levels().forCulture(500));
        var plots=Map.of(cell(0,0),1L,cell(3,0),1L,cell(8,0),2L);
        var result=CultureRegions.calculate(List.of(first,second),plots,Map.of());
        assertEquals(1L,result.get(cell(3,0)));assertEquals(1L,result.get(cell(0,0)));assertEquals(2L,result.get(cell(8,0)));
        assertEquals(2L,result.get(cell(6,0)));
    }
}
