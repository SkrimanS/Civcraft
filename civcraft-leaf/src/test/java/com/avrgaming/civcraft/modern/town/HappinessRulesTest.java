package com.avrgaming.civcraft.modern.town;
import java.io.File;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HappinessRulesTest {
    HappinessRules rules(){return new HappinessRules(new File("src/main/resources/civcraft/data/happiness.yml"));}
    @Test void usesFractionNotDifferenceAndRoundsBeforeSelectingState() {
        var rules=rules();assertEquals(5,rules.evaluate(.845,.155).state().level());
        assertEquals(4,rules.evaluate(.8449,.1551).state().level());
        assertEquals(6,rules.evaluate(12,1).state().level());assertEquals(3,rules.evaluate(12,12).state().level());
        assertEquals(.5,rules.evaluate(12,12).percentage());assertEquals(0,rules.evaluate(0,0).state().level());
    }
    @Test void computesOriginalResidentEmpireAndFractionalBiomeSources() {
        var r=rules();double happy=r.base(1)+2.825;
        double unhappy=r.population(3,4,2)+r.empire(2,1);
        assertEquals(14.825,happy);assertEquals(10,unhappy);
        var result=r.evaluate(happy,unhappy);assertEquals(3,result.state().level());
        assertEquals(1.3,r.evaluate(12,2).state().culture());assertEquals(1.3,r.evaluate(12,2).state().science());
    }
    @Test void connectedCultureReducesCapitalDistanceAndBothAreCapped() {
        var r=rules();assertEquals(2,r.distance(1000,true));assertEquals(14,r.distance(1000,false));
        assertEquals(15,r.distance(100000,true));assertEquals(15,r.distance(100000,false));
    }
    @Test void rejectsNonFiniteSourcesAndClampsNegativeTotals() {
        var r=rules();assertThrows(IllegalArgumentException.class,()->r.evaluate(Double.NaN,1));
        assertEquals(0,r.evaluate(-3,10).happy());assertEquals(0,r.evaluate(10,-3).unhappy());
    }
    @Test void loadsIndependentConfiguredRates()throws Exception {
        var file=java.nio.file.Files.createTempFile("happiness-rates-",".yml");
        try {
            java.nio.file.Files.writeString(file,"""
                happiness:
                  town_levels:
                    - level: 1
                      happiness: 12
                  states:
                    - level: 0
                      name: Test
                      amount: 0
                      hammer_rate: 0.33
                      beaker_rate: 0.44
                      coin_rate: 0.55
                      culture_rate: 0.66
                """);
            var state=new HappinessRules(file.toFile()).evaluate(12,1).state();
            assertEquals(.33,state.hammers());assertEquals(.44,state.science());assertEquals(.55,state.coins());assertEquals(.66,state.culture());
        }finally{java.nio.file.Files.deleteIfExists(file);}
    }
}
