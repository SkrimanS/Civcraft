package com.avrgaming.civcraft.modern.items;

/** Numeric rules from the original Attack, Defense, RangedAttack and DurabilityOnDeath components. */
public final class LegacyCombatRules {
    private LegacyCombatRules() {}
    public static double melee(double attack, boolean researched) {
        return Math.max(0.5, researched ? attack : attack / 2.0);
    }
    public static double ranged(double attack, double velocitySquared, boolean researched) {
        double damage = Math.min(attack, Math.max(0.0, velocitySquared) / 6.0 * attack);
        return Math.max(0.5, researched ? damage : damage / 2.0);
    }
    public static double defend(double damage, double defense, boolean researched) {
        return Math.max(0.5, damage - (researched ? defense : defense / 2.0));
    }
    /** A negative result means the item is destroyed. */
    public static int deathDamage(int maximum, int damage, double fraction) {
        int loss = (int) (maximum * fraction);
        return maximum - damage > loss ? damage + loss : -1;
    }
}
