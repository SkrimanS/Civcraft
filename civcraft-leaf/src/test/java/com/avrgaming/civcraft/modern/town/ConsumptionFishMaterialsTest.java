package com.avrgaming.civcraft.modern.town;
import java.util.Map;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsumptionFishMaterialsTest {
    @Test void allRawFishVariantsCountAsLegacy349WhileCookedFishDoesNot() {
        var mapping=ConsumptionContainers.reverse(Map.of(349,Material.COD,297,Material.BREAD));
        for(var raw:new Material[]{Material.COD,Material.SALMON,Material.TROPICAL_FISH,Material.PUFFERFISH})assertEquals(349,mapping.get(raw));
        assertEquals(297,mapping.get(Material.BREAD));assertFalse(mapping.containsKey(Material.COOKED_COD));assertFalse(mapping.containsKey(Material.COOKED_SALMON));
    }
    @Test void aliasesCannotSilentlyConsumeAnotherConfiguredFood() {
        assertThrows(IllegalArgumentException.class,()->ConsumptionContainers.reverse(Map.of(349,Material.COD,999,Material.SALMON)));
    }
}
