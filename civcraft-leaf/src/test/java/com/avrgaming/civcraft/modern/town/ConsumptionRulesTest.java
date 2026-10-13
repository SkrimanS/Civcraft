package com.avrgaming.civcraft.modern.town;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ConsumptionRulesTest {
    @Test void feedingGrowsAndStarvationReversesCounter() {
        var grown=ConsumptionRules.advance(1,0,24,48,null,true,true);assertEquals(1,grown.count());assertTrue(grown.consumed());
        var starved=ConsumptionRules.advance(1,1,24,48,null,false,false);assertEquals(0,starved.count());assertEquals(ConsumptionRules.Result.STARVE,starved.result());
        assertEquals(ConsumptionRules.Result.STAGNATE,ConsumptionRules.advance(1,0,24,48,null,false,false).result());
    }
    @Test void levelUpNeedsAnotherServingAfterFirstWasConsumed() {
        var up=ConsumptionRules.advance(1,23,24,48,null,true,true);assertEquals(2,up.level());assertEquals(0,up.count());
        var max=ConsumptionRules.advance(1,23,24,48,null,true,false);assertEquals(1,max.level());assertEquals(24,max.count());assertTrue(max.consumed());
    }
    @Test void highestLevelKeepsCounterAndLoweringUsesPreviousLimit() {
        var max=ConsumptionRules.advance(6,287,288,null,192,true,true);assertEquals(287,max.count());assertEquals(ConsumptionRules.Result.MAXED,max.result());
        var down=ConsumptionRules.advance(3,0,96,144,48,false,false);assertEquals(2,down.level());assertEquals(48,down.count());assertFalse(down.consumed());
    }
    @Test void unknownLevelDoesNotConsumeAndFoodAmountsTruncateWithMinimumOne() {
        assertFalse(ConsumptionRules.advance(1,0,null,null,null,true,true).consumed());
        assertEquals(1,ConsumptionRules.amount(2,.1));assertEquals(3,ConsumptionRules.amount(2,1.9));
        assertThrows(IllegalArgumentException.class,()->ConsumptionRules.amount(2,Double.POSITIVE_INFINITY));
    }
}
