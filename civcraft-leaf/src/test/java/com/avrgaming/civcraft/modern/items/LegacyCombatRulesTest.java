package com.avrgaming.civcraft.modern.items;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyCombatRulesTest {
    @Test void technologyHalvesAttackAndDefense() {
        assertEquals(20,LegacyCombatRules.melee(20,true));
        assertEquals(10,LegacyCombatRules.melee(20,false));
        assertEquals(7,LegacyCombatRules.defend(10,3,true));
        assertEquals(8.5,LegacyCombatRules.defend(10,3,false));
    }
    @Test void defenseIsFlatAndNeverErasesTheLastHalfPoint() {
        assertEquals(0.5,LegacyCombatRules.defend(4,8,true));
        assertEquals(0.5,LegacyCombatRules.defend(0.5,1,false));
        assertEquals(0.5,LegacyCombatRules.melee(0.1,true));
    }
    @Test void arrowUsesSquaredSpeedCapsAtBowAttackAndThenAppliesTechnology() {
        assertEquals(10,LegacyCombatRules.ranged(20,3,true));
        assertEquals(5,LegacyCombatRules.ranged(20,3,false));
        assertEquals(20,LegacyCombatRules.ranged(20,100,true));
        assertEquals(10,LegacyCombatRules.ranged(20,100,false));
        assertEquals(0.5,LegacyCombatRules.ranged(20,0,true));
    }
    @Test void deathUsesTruncatedMaximumDurabilityAndBreaksAtBoundary() {
        assertEquals(5,LegacyCombatRules.deathDamage(59,0,0.1));
        assertEquals(58,LegacyCombatRules.deathDamage(59,53,0.1));
        assertEquals(-1,LegacyCombatRules.deathDamage(59,54,0.1));
        assertEquals(-1,LegacyCombatRules.deathDamage(59,58,0.1));
    }
}
