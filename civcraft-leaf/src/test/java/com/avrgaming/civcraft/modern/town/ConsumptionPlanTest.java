package com.avrgaming.civcraft.modern.town;

import java.io.File;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsumptionPlanTest {
    private ConsumptionCatalog catalog() {
        return new ConsumptionCatalog(new File("src/main/resources/civcraft/data/structures.yml"));
    }
    @Test void originalLevelsAndAllFoodCostsAreReadFromShippedConfiguration() {
        var catalog=catalog();
        assertEquals(6,catalog.levels("ti_cottage").size());
        assertEquals(Map.of(297,5,391,6,392,6,319,1),catalog.levels("ti_cottage").get(6).food());
        assertEquals(288,catalog.levels("ti_cottage").get(6).count());
        assertEquals(600,catalog.levels("ti_cottage").get(6).output());
        assertEquals(5,catalog.levels("s_temple").size());
        assertEquals(Map.of(423,10),catalog.levels("s_temple").get(5).food());
        assertThrows(UnsupportedOperationException.class,()->catalog.levels("s_temple").clear());
    }
    @Test void missingOneIngredientDoesNotConsumeAnyOtherFood() {
        var stock=Map.of(297,10L,392,10L);
        var cycle=ConsumptionPlan.cycle(catalog().levels("ti_cottage"),2,1,stock,1,List.of());
        assertEquals(stock,cycle.remaining());assertEquals(ConsumptionRules.Result.STARVE,cycle.state().result());
        assertEquals(0,cycle.rawOutput());
    }
    @Test void enoughFoodLevelsUpButOnlyOneCurrentLevelServingIsRemovedAndPaid() {
        var cycle=ConsumptionPlan.cycle(catalog().levels("s_temple"),1,23,Map.of(423,4L),1,List.of());
        assertEquals(ConsumptionRules.Result.LEVELUP,cycle.state().result());
        assertEquals(2,cycle.state().level());assertEquals(Map.of(423,2L),cycle.remaining());
        assertEquals(100,cycle.rawOutput());
    }
    @Test void aSingleServingReachesThresholdWithoutLevelingUp() {
        var cycle=ConsumptionPlan.cycle(catalog().levels("s_temple"),1,23,Map.of(423,2L),1,List.of());
        assertEquals(ConsumptionRules.Result.MAXED,cycle.state().result());
        assertEquals(1,cycle.state().level());assertEquals(24,cycle.state().count());
        assertTrue(cycle.remaining().isEmpty());assertEquals(100,cycle.rawOutput());
    }
    @Test void starvationLowersLevelWithoutOutputAndHighestLevelStillEats() {
        var levels=catalog().levels("s_temple");
        var down=ConsumptionPlan.cycle(levels,2,0,Map.of(),1,List.of());
        assertEquals(ConsumptionRules.Result.LEVELDOWN,down.state().result());
        assertEquals(24,down.state().count());assertEquals(0,down.rawOutput());
        var max=ConsumptionPlan.cycle(levels,5,143,Map.of(423,12L),1,List.of());
        assertEquals(ConsumptionRules.Result.MAXED,max.state().result());
        assertEquals(143,max.state().count());assertEquals(500,max.rawOutput());
        assertEquals(Map.of(423,2L),max.remaining());
    }
    @Test void fishingExchangeReturnsBreadChangeAndPreservesUnrelatedStock() {
        var fish=new ConsumptionPlan.Exchange(297,349,3);
        var meal=ConsumptionPlan.meal(Map.of(297,5,391,1),Map.of(297,1L,349,4L,391,1L,264,8L),1,List.of(fish));
        assertTrue(meal.enough());assertEquals(Map.of(297,2L,349,2L,264,8L),meal.remaining());
        assertThrows(UnsupportedOperationException.class,()->meal.remaining().clear());
    }
    @Test void fishingNeverReplacesOtherFoodAndUsesBreadFirst() {
        var fish=new ConsumptionPlan.Exchange(297,349,3);
        var stock=Map.of(349,10L);
        assertFalse(ConsumptionPlan.meal(Map.of(297,1,391,1),stock,1,List.of(fish)).enough());
        assertEquals(stock,ConsumptionPlan.meal(Map.of(297,1,391,1),stock,1,List.of(fish)).remaining());
        assertEquals(Map.of(349,10L),ConsumptionPlan.meal(Map.of(297,1),Map.of(297,1L,349,10L),1,List.of(fish)).remaining());
    }
    @Test void consumptionModifiersApplyOnceToBothChecksAndRemoval() {
        assertEquals(Map.of(423,7L),ConsumptionPlan.meal(Map.of(423,6),Map.of(423,10L),.5,List.of()).remaining());
        assertEquals(Map.of(423,6L),ConsumptionPlan.meal(Map.of(423,3),Map.of(423,10L),1.5,List.of()).remaining());
    }
    @Test void largeStockDoesNotOverflowAndInvalidSharedExchangesAreRejected() {
        var fish=new ConsumptionPlan.Exchange(297,349,3);
        var meal=ConsumptionPlan.meal(Map.of(297,5),Map.of(349,Long.MAX_VALUE),1,List.of(fish));
        assertEquals(Long.MAX_VALUE-2,meal.remaining().get(349));
        assertThrows(IllegalArgumentException.class,()->ConsumptionPlan.meal(Map.of(297,1,349,1),Map.of(),1,List.of(fish)));
        assertThrows(IllegalArgumentException.class,()->ConsumptionPlan.meal(Map.of(297,1,391,1),Map.of(),1,List.of(fish,new ConsumptionPlan.Exchange(391,349,2))));
    }
    @Test void unknownLevelLeavesFoodUntouchedWithoutOutput() {
        var stock=Map.of(423,10L);
        var cycle=ConsumptionPlan.cycle(catalog().levels("s_temple"),6,0,stock,1,List.of());
        assertEquals(ConsumptionRules.Result.UNKNOWN,cycle.state().result());
        assertEquals(stock,cycle.remaining());assertEquals(0,cycle.rawOutput());
    }
}
