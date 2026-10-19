package com.avrgaming.civcraft.modern.war;

public final class TowerCombat {
    private TowerCombat() {}
    /** ProjectileComponent.getDamage truncates after the Fire Bomb multiplier. */
    public static int arrowDamage(int base,double bonus) {
        double amount=base*(1+bonus);
        if(base<0||!Double.isFinite(bonus)||bonus<0||!Double.isFinite(amount)||amount>Integer.MAX_VALUE)throw new IllegalArgumentException("Неверный урон стрелковой башни");
        return (int)amount;
    }
}
