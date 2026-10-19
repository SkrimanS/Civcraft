package com.avrgaming.civcraft.modern.town;
public final class GrowthRules {
    private GrowthRules() {}
    public static double total(double raw,double government,boolean fertilizer,double bonuses) {
        if(!Double.isFinite(raw) || !Double.isFinite(government) || !Double.isFinite(bonuses))throw new IllegalArgumentException("Неверный источник роста");
        double result=raw*(government+(fertilizer?.3:0)+bonuses);
        if(!Double.isFinite(result))throw new IllegalArgumentException("Переполнение роста");
        return Math.max(0,result);
    }
}
