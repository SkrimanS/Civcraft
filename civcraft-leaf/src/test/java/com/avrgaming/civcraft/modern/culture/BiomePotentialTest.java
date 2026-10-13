package com.avrgaming.civcraft.modern.culture;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BiomePotentialTest {
    BiomePotential catalog(){return new BiomePotential(YamlConfiguration.loadConfiguration(new File("src/main/resources/civcraft/data/culture.yml")).getMapList("culture_biomes"));}
    @Test void keepsOriginalPlainAndUnknownYields() {
        var c=catalog();var plains=c.yield("minecraft:plains");
        assertEquals(.25,plains.hammers());assertEquals(2,plains.growth());assertEquals(.025,plains.happiness());assertEquals(.5,plains.beakers());
        assertEquals(new BiomePotential.Yield(.2,.2,.2,.01,.2),c.yield("unconfigured_biome"));
    }
    @Test void modernRenamesRetainConfiguredLegacyFamilies() {
        var c=catalog();assertEquals(c.yield("MUTATED_PLAINS"),c.yield("sunflower_plains"));
        assertEquals(c.yield("MESA"),c.yield("badlands"));assertEquals(c.yield("SWAMPLAND"),c.yield("mangrove_swamp"));
        assertEquals(c.yield("DEEP_OCEAN"),c.yield("deep_cold_ocean"));assertEquals(c.yield("TAIGA_COLD"),c.yield("snowy_taiga"));
    }
    @Test void addsIndependentChunkContributionsWithoutRounding() {
        var c=catalog();var sum=c.yield("plains").add(c.yield("ocean"));
        assertEquals(.75,sum.hammers());assertEquals(2.25,sum.growth());assertEquals(.045,sum.happiness(),1e-10);assertEquals(1.5,sum.beakers());
    }
    @Test void rejectsMissingFallbackAndNonFiniteBiomeConfiguration() {
        assertThrows(IllegalArgumentException.class,()->new BiomePotential(List.of()));
        var invalid=Map.of("name","UNKNOWN","coins",0,"hammers",Double.POSITIVE_INFINITY,"growth",0,"happiness",0,"beakers",0);
        assertThrows(IllegalArgumentException.class,()->new BiomePotential(List.of(invalid)));
    }
}
