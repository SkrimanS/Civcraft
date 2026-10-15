package com.avrgaming.civcraft.modern.town;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.avrgaming.civcraft.modern.town.FarmGrowthPlan.*;

class FarmGrowthPlanTest {
    private final FarmGrowthPlan plan=new FarmGrowthPlan(6);
    private static Plant plant(Crop crop,int age){return new Plant(new Position("farm_world",160,100,-160),crop,age,List.of());}
    private static final class Rolls extends Random {
        private final Queue<Integer> rolls=new ArrayDeque<>();
        Rolls(Integer... values){rolls.addAll(Arrays.asList(values));}
        @Override public int nextInt(int bound){assertFalse(rolls.isEmpty(),"Unexpected random draw");int value=rolls.remove();assertTrue(value>=0 && value<bound);return value;}
    }
    @Test void originalBudgetKeepsUnscaledTownRemainder(){
        assertEquals(new Budget(1.5,9,0),plan.budget(100,1.5));
        assertEquals(new Budget(1.875,11,25),plan.budget(125,1.5));
        assertEquals(new Budget(.25,1,25),plan.budget(25,1));
        assertEquals(new Budget(0,0,25),plan.budget(25,0));
        assertThrows(IllegalArgumentException.class,()->plan.budget(Double.NaN,1));
        assertThrows(IllegalArgumentException.class,()->plan.budget(-1,1));
        assertThrows(IllegalArgumentException.class,()->plan.budget(Double.MAX_VALUE,2));
    }
    @Test void repeatedSelectionsUseOneSnapshotAgeAndPreserveWorld(){
        var changes=plan.plan(List.of(plant(Crop.WHEAT,0)),100,1,true,new Rolls(0,0,0,0,0,0));
        assertEquals(List.of(new Change(new Position("farm_world",160,100,-160),"WHEAT",1,false)),changes);
    }
    @Test void allAgeCapsAndInactiveFarms(){
        for(var crop:Crop.values())if(!crop.stem()){
            var growing=plant(crop,crop.maximum()-1);
            assertEquals(crop.maximum(),plan.plan(List.of(growing),100,1,true,new Random(1)).getFirst().age());
            assertTrue(plan.plan(List.of(plant(crop,crop.maximum())),100,1,true,new Rolls()).isEmpty());
            assertTrue(plan.plan(List.of(growing),100,1,false,new Rolls()).isEmpty());
        }
    }
    @Test void fractionalDrawUsesOriginalThreshold(){
        var wheat=plant(Crop.WHEAT,0);
        // Zero guaranteed selections; the old town remainder still grants a chance.
        assertEquals(1,plan.plan(List.of(wheat),25,0,true,new Rolls(24,0)).size());
        assertTrue(plan.plan(List.of(wheat),25,0,true,new Rolls(25)).isEmpty());
    }
    @Test void stemsRequireAllFourNeighborsAndRejectExistingMatchingFruit(){
        var p=new Position("farm_world",160,-20,160);
        for(var crop:List.of(Crop.MELON_STEM,Crop.PUMPKIN_STEM)){
            assertFalse(new Plant(p,crop,0,Arrays.asList(null,"AIR","AIR","AIR")).eligible());
            assertFalse(new Plant(p,crop,0,List.of(crop.fruit(),"AIR","AIR","AIR")).eligible());
            assertFalse(new Plant(p,crop,7,List.of("STONE","STONE","STONE","STONE")).eligible());
            assertTrue(new Plant(p,crop,0,List.of("AIR","STONE","STONE","STONE")).eligible());
        }
    }
    @Test void matureStemHasTwentyPercentChanceAndSelectsOnlyOneDirection(){
        var p=new Position("farm_world",160,-20,160);
        var stem=new Plant(p,Crop.PUMPKIN_STEM,7,List.of("AIR","STONE","STONE","STONE"));
        var single=new FarmGrowthPlan(1);
        assertTrue(single.plan(List.of(stem),100,1,true,new Rolls(0,7)).isEmpty());
        assertTrue(single.plan(List.of(stem),100,1,true,new Rolls(0,8,1)).isEmpty());
        assertEquals(List.of(new Change(new Position("farm_world",159,-20,160),"PUMPKIN",0,true)),single.plan(List.of(stem),100,1,true,new Rolls(0,8,0)));
    }
    @Test void oldSpawnSquareExcludesInteriorButNotBoundary(){
        for(int x:new int[]{-63,0,63}){
            var wheat=new Plant(new Position("world",x,70,0),Crop.WHEAT,0,List.of());
            assertTrue(plan.plan(List.of(wheat),100,1,true,new Random(1)).isEmpty());
        }
        for(int x:new int[]{-64,64}){
            var wheat=new Plant(new Position("world",x,70,0),Crop.WHEAT,0,List.of());
            assertEquals(1,plan.plan(List.of(wheat),100,1,true,new Random(1)).size());
        }
    }
}
