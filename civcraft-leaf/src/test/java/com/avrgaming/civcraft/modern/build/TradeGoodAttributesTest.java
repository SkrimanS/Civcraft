package com.avrgaming.civcraft.modern.build;
import java.io.File;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradeGoodAttributesTest {
    TradeGoodAttributes forge() {
        var raw=YamlConfiguration.loadConfiguration(new File("src/main/resources/civcraft/data/structures.yml")).getMapList("structures").stream().filter(row->"s_blacksmith".equals(row.get("id"))).findFirst().orElseThrow();
        return TradeGoodAttributes.parse(raw.get("components"));
    }
    @Test void configuredForgeAddsOneHundredForEveryCopperOilAndSilverInstance() {
        assertEquals(400,forge().calculate(Map.of("good_copper",2,"good_oil",1,"good_silver",1,"good_grapes",3)).get("HAMMERS"));
        assertEquals(0,forge().calculate(Map.of("good_grapes",3)).get("HAMMERS"));
    }
    @Test void removingHeldGoodsImmediatelyRemovesTheirDynamicContribution() {
        var forge=forge();assertEquals(200,forge.calculate(Map.of("good_oil",2)).get("HAMMERS"));assertEquals(100,forge.calculate(Map.of("good_oil",1)).get("HAMMERS"));assertEquals(0,forge.calculate(Map.of()).get("HAMMERS"));
        assertEquals(0,TradeGoodAttributes.NONE.calculate(Map.of("good_oil",2)).get("HAMMERS"));
    }
    @Test void componentRulesComposeAndLeaveStaticComponentsForTheirOwnParser() {
        var raw=List.of(Map.of("name","AttributeTradeGood","attribute","GROWTH","value","-0.2","goods"," good_oil,good_silver "),Map.of("name","AttributeTradeGood","attribute","HAMMERS","value","25","goods","good_oil"),Map.of("name","AttributeStatic","attribute","HAMMERS","value","50"));
        var dynamic=TradeGoodAttributes.parse(raw).calculate(Map.of("good_oil",2,"good_silver",1));
        assertEquals(-.6,dynamic.get("GROWTH"),1e-9);assertEquals(50,dynamic.get("HAMMERS"));assertEquals(50,StaticAttributes.parse(raw).get("HAMMERS"));
    }
    @Test void malformedRulesAndNegativeCountsCannotProduceProduction() {
        assertThrows(IllegalArgumentException.class,()->TradeGoodAttributes.parse(List.of(Map.of("name","AttributeTradeGood","attribute","HAMMERS","value","NaN","goods","good_oil"))));
        assertThrows(IllegalArgumentException.class,()->forge().calculate(Map.of("good_oil",-1)));
    }
}
