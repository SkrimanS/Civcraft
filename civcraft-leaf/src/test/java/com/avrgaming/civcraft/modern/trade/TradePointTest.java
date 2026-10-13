package com.avrgaming.civcraft.modern.trade;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradePointTest {
    @Test void understandsOriginalBankAndMarketMarkers() {
        assertEquals(new TradePoint("bank","iron"),TradePoint.parse(List.of("/sign","sell:iron","","")).orElseThrow());
        assertEquals(new TradePoint("buybig","85"),TradePoint.parse(List.of("/bigbig","id:85","","")).orElseThrow());
        assertEquals(new TradePoint("sell","0"),TradePoint.parse(List.of("/sell","id:0","","")).orElseThrow());
        assertTrue(TradePoint.parse(List.of("Купить","Камень","4 монеты","1 шт.")).isEmpty());
        assertTrue(TradePoint.parse(List.of("/buy","id:nope")).isEmpty());
    }
}
