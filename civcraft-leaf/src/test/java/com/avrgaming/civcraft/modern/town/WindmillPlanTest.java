package com.avrgaming.civcraft.modern.town;

import java.io.File;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.avrgaming.civcraft.modern.town.WindmillPlan.Crop.*;

class WindmillPlanTest {
    final WindmillPlan planner=new WindmillPlan(new File("src/main/resources/civcraft/data/structures.yml"));
    List<WindmillPlan.Spot> beds(int count){return IntStream.range(0,count).mapToObj(i->new WindmillPlan.Spot(1,"world",i,-63,0)).toList();}
    @Test void shippedCapacityDoublesWithMachineryAndSelectsUniqueBeds() {
        var stock=Map.of(WHEAT,100L,CARROTS,100L,POTATOES,100L);
        var ordinary=planner.plan(beds(100),stock,false,false,new Random(1));var machinery=planner.plan(beds(100),stock,true,false,new Random(1));
        assertEquals(16,ordinary.plants().size());assertEquals(32,machinery.plants().size());assertEquals(32,machinery.plants().stream().map(WindmillPlan.Plant::spot).distinct().count());
        assertEquals(284,ordinary.remaining().values().stream().mapToLong(Long::longValue).sum());assertEquals(268,machinery.remaining().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(300,stock.values().stream().mapToLong(Long::longValue).sum());
    }
    @Test void seedAvailabilityAndActualBedsLimitThePlanAndDuplicatePositionsSpendOnce() {
        assertEquals(3,planner.plan(beds(100),Map.of(WHEAT,3L),true,false,new Random(2)).plants().size());
        assertEquals(2,planner.plan(beds(2),Map.of(CARROTS,100L),true,false,new Random(2)).plants().size());
        var duplicate=List.of(new WindmillPlan.Spot(1,"world",0,180,0),new WindmillPlan.Spot(2,"world",0,180,0));
        var result=planner.plan(duplicate,Map.of(POTATOES,2L),false,false,new Random(2));assertEquals(1,result.plants().size());assertEquals(1,result.remaining().get(POTATOES));
    }
    @Test void unavailablePreferredCropFallsBackInOriginalOrderWithoutConsumingAbsentSeeds() {
        Random onlyWheat=new Random(){@Override public int nextInt(int bound){return 0;}};
        var result=new WindmillPlan(3).plan(beds(3),Map.of(CARROTS,1L,POTATOES,2L),false,false,onlyWheat);
        assertEquals(List.of(CARROTS,POTATOES,POTATOES),result.plants().stream().map(WindmillPlan.Plant::crop).toList());
        assertTrue(result.remaining().values().stream().allMatch(v->v==0));
        assertEquals(WHEAT,WindmillPlan.Crop.seed("WHEAT_SEEDS").orElseThrow());assertTrue(WindmillPlan.Crop.seed("WHEAT").isEmpty());assertTrue(WindmillPlan.Crop.seed("POISONOUS_POTATO").isEmpty());
    }
    @Test void warAndEmptyStockNeverSelectBedsOrSpendSeeds() {
        Random unused=new Random(){@Override public int nextInt(int bound){throw new AssertionError("No random work during war or starvation");}};
        var result=planner.plan(beds(30),Map.of(WHEAT,100L),true,true,unused);assertTrue(result.plants().isEmpty());assertEquals(100,result.remaining().get(WHEAT));
        assertTrue(planner.plan(beds(30),Map.of(),true,false,unused).plants().isEmpty());assertThrows(IllegalArgumentException.class,()->planner.plan(beds(1),Map.of(WHEAT,-1L),false,false,unused));
    }
}
