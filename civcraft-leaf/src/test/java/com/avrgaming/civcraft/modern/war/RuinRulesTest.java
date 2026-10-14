package com.avrgaming.civcraft.modern.war;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RuinRulesTest {
    IntSupplier rolls(int... values){return new IntSupplier(){int i;public int getAsInt(){return values[i++];}};}
    @Test void originalInclusiveBranchBoundariesArePreserved() {
        assertEquals(new RuinRules.Result("AIR",false),RuinRules.choose("STONE",rolls(70)));
        assertEquals(new RuinRules.Result("GRAVEL",false),RuinRules.choose("STONE",rolls(71,30)));
        assertEquals(new RuinRules.Result("FIRE",false),RuinRules.choose("STONE",rolls(71,31,10)));
        assertEquals(new RuinRules.Result("STONE",true),RuinRules.choose("STONE",rolls(71,31,11,1)));
        assertEquals(new RuinRules.Result("STONE",false),RuinRules.choose("STONE",rolls(99,99,99,2)));
    }
    @Test void ValuableStructuralBlocksAlwaysCrumbleWithoutRandomRolls() {
        for(var material:new String[]{"BEDROCK","DIAMOND_BLOCK","REDSTONE_BLOCK","WET_SPONGE","BEACON"})
            assertEquals(new RuinRules.Result("GRAVEL",false),RuinRules.choose(material,()->{throw new AssertionError("Must not roll");}));
    }
}
