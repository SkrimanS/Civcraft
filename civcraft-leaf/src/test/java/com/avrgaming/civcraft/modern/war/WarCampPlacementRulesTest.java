package com.avrgaming.civcraft.modern.war;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WarCampPlacementRulesTest {
    @Test void directionPreservesLegacyCardinalAndDiagonalChoices() {
        assertEquals("south",WarCampPlacementRules.direction(0));assertEquals("west",WarCampPlacementRules.direction(45));assertEquals("west",WarCampPlacementRules.direction(90));assertEquals("west",WarCampPlacementRules.direction(135));assertEquals("north",WarCampPlacementRules.direction(180));assertEquals("east",WarCampPlacementRules.direction(225));assertEquals("east",WarCampPlacementRules.direction(270));assertEquals("east",WarCampPlacementRules.direction(315));assertEquals("south",WarCampPlacementRules.direction(-360));
    }
    @Test void directionalOriginsSnapToChunksBeforeApplyingOutwardAndVerticalShifts() {
        assertEquals(new WarCampPlacementRules.Origin(16,79,48,"south"),WarCampPlacementRules.origin(40.5,80.8,50.5,0,32,32));
        assertEquals(new WarCampPlacementRules.Origin(0,79,32,"west"),WarCampPlacementRules.origin(40.5,80.8,50.5,90,32,32));
        assertEquals(new WarCampPlacementRules.Origin(16,79,16,"north"),WarCampPlacementRules.origin(40.5,80.8,50.5,180,32,32));
        assertEquals(new WarCampPlacementRules.Origin(32,79,32,"east"),WarCampPlacementRules.origin(40.5,80.8,50.5,270,32,32));
        assertEquals(new WarCampPlacementRules.Origin(-32,79,-16,"south"),WarCampPlacementRules.origin(-.5,80,-.5,0,32,32));
    }
    @Test void heightMatchesSourcePlayerLimitTemplateLimitAndBedrockShift() {
        assertDoesNotThrow(()->WarCampPlacementRules.height(2,1,21,1,320));assertDoesNotThrow(()->WarCampPlacementRules.height(199.9,198,21,1,320));
        assertThrows(IllegalArgumentException.class,()->WarCampPlacementRules.height(200,199,21,1,320));assertThrows(IllegalArgumentException.class,()->WarCampPlacementRules.height(1,0,21,1,320));assertThrows(IllegalArgumentException.class,()->WarCampPlacementRules.height(180,179,76,1,320));assertThrows(IllegalArgumentException.class,()->WarCampPlacementRules.height(180,179,21,1,200));
    }
    @Test void averageTerrainAllowsExactTenBlockBoundaryAndRejectsInvalidGeometry() {
        assertDoesNotThrow(()->WarCampPlacementRules.terrain(90,80));assertDoesNotThrow(()->WarCampPlacementRules.terrain(70,80));assertThrows(IllegalArgumentException.class,()->WarCampPlacementRules.terrain(91,80));assertThrows(IllegalArgumentException.class,()->WarCampPlacementRules.terrain(69,80));assertThrows(IllegalArgumentException.class,()->WarCampPlacementRules.terrain(80,Double.NaN));assertThrows(IllegalArgumentException.class,()->WarCampPlacementRules.origin(0,80,0,Float.NaN,32,32));assertThrows(IllegalArgumentException.class,()->WarCampPlacementRules.origin(0,80,0,0,0,32));
    }
}
