package com.avrgaming.civcraft.modern.town;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GrowthRulesTest {
    @Test void fertilizerAddsThirtyPercentagePointsToGovernmentRate() {
        assertEquals(50,GrowthRules.total(100,.5,false,0));assertEquals(80,GrowthRules.total(100,.5,true,0));
        assertEquals(180,GrowthRules.total(100,1.5,true,0));
    }
    @Test void sumsBonusesAndKeepsFractionalGrowthWithoutNegativeOutput() {
        assertEquals(2.625,GrowthRules.total(1.5,1.2,true,.25));assertEquals(0,GrowthRules.total(-10,1,false,0));
        assertThrows(IllegalArgumentException.class,()->GrowthRules.total(Double.NaN,1,false,0));
    }
}
