package com.avrgaming.civcraft.modern.trade;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketPriceTest {
    @Test void initialBuyHasMarkupAndOriginalTwofoldCost() {
        var price=MarketPrice.initial(1,0.15);
        assertEquals(2,price.buy());assertEquals(1,price.sell());
        var quote=price.trade(true,64,1,512,0.15,true);
        assertEquals(256,quote.coins());assertEquals(64,quote.next().counter());
    }
    @Test void thresholdChangesSharedPriceAndSalesUseEachActualPrice() {
        var before=new MarketPrice(3,4,-500);
        var quote=before.trade(false,64,1,512,0.15,true);
        assertEquals(140,quote.coins());assertEquals(2,quote.next().sell());assertEquals(-52,quote.next().counter());
        var purchase=new MarketPrice(1,2,511).trade(true,1,1,512,0.15,true);
        assertEquals(4,purchase.coins());assertEquals(2,purchase.next().sell());assertEquals(0,purchase.next().counter());
    }
    @Test void minimumPriceAndNonStackableGoodsFollowOriginalSteps() {
        var quote=new MarketPrice(1,2,-511).trade(false,1,1,512,0.15,true);
        assertEquals(1,quote.next().sell());assertEquals(2,quote.next().buy());
        var single=MarketPrice.initial(10,0.15).trade(true,1,2,512,0.15,false);
        assertEquals(12,single.next().sell());assertEquals(0,single.next().counter());
    }
}
