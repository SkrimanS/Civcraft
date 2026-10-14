package com.avrgaming.civcraft.modern.trade;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradeShipMarkerTest {
    @Test void mixedCaseTemplateCommandsAndInvalidIdsAreHandled() {
        assertEquals(new TradeShipMarker(TradeShipMarker.Kind.IN_SIGN,2),TradeShipMarker.parse(List.of("/inSign","id:2","","")).orElseThrow());
        assertEquals(new TradeShipMarker(TradeShipMarker.Kind.OUTGOING,3),TradeShipMarker.parse(List.of(" /outgoing ","ID: 3")).orElseThrow());
        assertTrue(TradeShipMarker.parse(List.of("/incoming","id:-1")).isEmpty());
        assertTrue(TradeShipMarker.parse(List.of("/incoming","id:bad")).isEmpty());
        assertTrue(TradeShipMarker.parse(List.of("/chest","id:1")).isEmpty());
    }
    @Test void purchasedAndEarnedLevelsExposeDifferentPhysicalChestHalves() {
        var incoming=new TradeShipMarker(TradeShipMarker.Kind.INCOMING,2);
        var outgoing=new TradeShipMarker(TradeShipMarker.Kind.OUTGOING,2);
        assertTrue(incoming.chest());assertFalse(incoming.open(2,10));assertTrue(incoming.open(3,1));
        assertFalse(outgoing.open(6,4));assertTrue(outgoing.open(1,5));
    }
    @Test void signsExplainDistinctUnlockConditionsInRussian() {
        var incoming=new TradeShipMarker(TradeShipMarker.Kind.IN_SIGN,2);
        var outgoing=new TradeShipMarker(TradeShipMarker.Kind.OUT_SIGN,2);
        assertEquals(List.of("Входящий груз","Недоступно","Улучшение 3","торгового судна"),incoming.text(2,10));
        assertEquals(List.of("Исходящий груз","Недоступно","Уровень 5","торгового судна"),outgoing.text(6,4));
        assertEquals(List.of("Исходящий груз","3","",""),outgoing.text(1,5));
        assertEquals(List.of("Входящий груз","3","4",""),new TradeShipMarker(TradeShipMarker.Kind.IN,1).text(1,1));
    }
}
