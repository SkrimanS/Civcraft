package com.avrgaming.civcraft.modern.trade;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradeShipProgressTest {
    final Map<Integer,TradeShipProgress.Level> levels=Map.of(
            1,new TradeShipProgress.Level(1,1024,2,.1),
            2,new TradeShipProgress.Level(2,2048,2,.2));
    @Test void consumesWholeTradeableStacksRatherThanItemsOrMandatoryFullQuota() {
        var slots=Arrays.asList(null,new TradeShipProgress.Cargo(64,false,100),
                new TradeShipProgress.Cargo(7,true,3),new TradeShipProgress.Cargo(64,true,2),
                new TradeShipProgress.Cargo(64,true,100));
        var result=TradeShipProgress.consume(levels,new TradeShipProgress.State(1,0),1,slots);
        assertEquals(List.of(2,3),result.consumedSlots());assertEquals(71,result.consumed());
        assertEquals(149,result.money());assertEquals(7,result.culture());
        assertEquals(new TradeShipProgress.State(1,71),result.state());
        assertEquals(TradeShipProgress.Result.GROW,result.result());
        var partial=TradeShipProgress.consume(levels,new TradeShipProgress.State(1,0),1,List.of(new TradeShipProgress.Cargo(1,true,5)));
        assertEquals(1,partial.consumed());assertEquals(TradeShipProgress.Result.GROW,partial.result());
    }
    @Test void upgradeExpandsQuotaAndLevelUpUsesNewCultureWithCarriedCount() {
        var result=TradeShipProgress.consume(levels,new TradeShipProgress.State(1,1020),2,
                Collections.nCopies(5,new TradeShipProgress.Cargo(64,true,1)));
        assertEquals(256,result.consumed());assertEquals(new TradeShipProgress.State(2,252),result.state());
        assertEquals(TradeShipProgress.Result.LEVELUP,result.result());assertEquals(102,result.culture());
    }
    @Test void maximumStillTradesButFreezesCounterAndNoCargoStagnates() {
        var state=new TradeShipProgress.State(2,2040);
        var result=TradeShipProgress.consume(levels,state,1,List.of(new TradeShipProgress.Cargo(64,true,1)));
        assertEquals(TradeShipProgress.Result.MAXED,result.result());assertEquals(state,result.state());assertEquals(64,result.money());
        assertEquals(TradeShipProgress.Result.STAGNATE,TradeShipProgress.consume(levels,state,1,List.of()).result());
    }
    @Test void chestGatesUseIndependentPurchasedAndEarnedLevels() {
        assertTrue(TradeShipProgress.inputChest(1,2));assertFalse(TradeShipProgress.inputChest(2,2));
        assertTrue(TradeShipProgress.outputChest(1,3));assertFalse(TradeShipProgress.outputChest(1,2));
        assertFalse(TradeShipProgress.inputChest(-1,6));assertFalse(TradeShipProgress.outputChest(-1,10));
    }
}
