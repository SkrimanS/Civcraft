package com.avrgaming.civcraft.modern.trade;
import java.io.File;
import java.util.*;
import com.avrgaming.civcraft.modern.town.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GoodieFoodTest {
    final GoodieEffects effects=new GoodieEffects(new TradeGoodsCatalog(new File("src/main/resources/civcraft/data/goods.yml")),new File("src/main/resources/civcraft/data/buffs.yml"));
    @Test void repeatedSpiceDoesNotReduceFoodAgainAndPyramidMultipliesItsReduction() {
        var spice=effects.calculate(List.of("good_spice","good_spice"));
        assertEquals(.5,spice.cottageFood(1));assertEquals(.125,spice.cottageFood(.25));
        var food=Map.of(297,8);
        assertTrue(ConsumptionPlan.meal(food,Map.of(297,1L),spice.cottageFood(.25),spice.cottageExchanges()).enough());
        assertFalse(ConsumptionPlan.meal(food,Map.of(297,1L),spice.cottageFood(1),spice.cottageExchanges()).enough());
        assertTrue(ConsumptionPlan.meal(food,Map.of(297,4L),spice.cottageFood(1),spice.cottageExchanges()).remaining().isEmpty());
    }
    @Test void fishReplacesBreadAtTwoToOneWithBreadChangeAndStopsWhenGoodieIsRemoved() {
        var fish=effects.calculate(List.of("good_crabs","good_salmon"));
        var meal=ConsumptionPlan.meal(Map.of(297,5),Map.of(349,3L),fish.cottageFood(1),fish.cottageExchanges());
        assertTrue(meal.enough());assertEquals(Map.of(297,1L),meal.remaining());
        assertFalse(ConsumptionPlan.meal(Map.of(297,5),Map.of(349,3L),1,GoodieEffects.Values.NONE.cottageExchanges()).enough());
    }
    @Test void configuredCottageLevelsUseReducedFoodAndFishWithoutAlteringOtherRequirements() {
        var levels=new ConsumptionCatalog(new File("src/main/resources/civcraft/data/structures.yml")).levels("ti_cottage");
        var goods=effects.calculate(List.of("good_spice","good_crabs"));
        for(var level:levels.values()) {
            var stock=new HashMap<Integer,Long>();
            level.food().forEach((id,amount)->stock.put(id,(long)ConsumptionRules.amount(amount,goods.cottageFood(1))));
            long bread=stock.remove(297);stock.put(349,(bread+1)/2);
            var meal=ConsumptionPlan.meal(level.food(),stock,goods.cottageFood(1),goods.cottageExchanges());
            assertTrue(meal.enough());assertEquals(bread%2,meal.remaining().getOrDefault(297,0L));
            assertFalse(meal.remaining().containsKey(349));
        }
    }
}
