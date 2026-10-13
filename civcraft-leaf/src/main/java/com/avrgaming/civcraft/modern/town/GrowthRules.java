package com.avrgaming.civcraft.modern.town;
public final class GrowthRules {
    private GrowthRules() {}
    public static double total(double raw,double government,boolean fertilizer,double bonuses) {
        if(!Double.isFinite(raw) || !Double.isFinite(government) || !Double.isFinite(bonuses))throw new IllegalArgumentException("Invalid growth source");
        double result=raw*(government+(fertilizer?.3:0)+bonuses);
        if(!Double.isFinite(result))throw new IllegalArgumentException("Growth overflow");
        return Math.max(0,result);
    }
}
