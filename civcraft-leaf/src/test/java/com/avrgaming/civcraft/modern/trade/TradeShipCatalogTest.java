package com.avrgaming.civcraft.modern.trade;

import java.io.File;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradeShipCatalogTest {
    @Test void originalConfigurationSeparatesStackQuotaFromItemThreshold() {
        var levels=new TradeShipCatalog(new File("src/main/resources/civcraft/data/structures.yml")).levels();
        assertEquals(10,levels.size());
        assertEquals(new TradeShipProgress.Level(1,1024,2,.1),levels.get(1));
        assertEquals(new TradeShipProgress.Level(10,163840,10,1),levels.get(10));
        assertEquals(4,levels.get(3).stacks());assertEquals(4096,levels.get(3).threshold());
    }
}
