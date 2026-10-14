package com.avrgaming.civcraft.modern.build;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TowerMarkerTest {
    @Test void recognizesOnlyTurretCommandsAndRetainsPlayerFacingSigns(){assertTrue(TowerMarker.firing(List.of(" /TOWERFIRE ","","","")));assertFalse(TowerMarker.firing(List.of()));assertFalse(TowerMarker.firing(List.of("/chest","id:1")));assertFalse(TowerMarker.firing(List.of("Башня лучников")));assertFalse(TowerMarker.firing(List.of("/towerfire extra")));}
}
