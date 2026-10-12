package com.avrgaming.civcraft.modern.research;

public record ResearchProgress(long civId, String techId, double progress, double requiredBeakers) {
    public double percent() {
        if (requiredBeakers <= 0) {
            return 100.0;
        }
        return Math.min(100.0, (progress / requiredBeakers) * 100.0);
    }
}
