package com.avrgaming.civcraft.modern.build;

public record StructureDefinition(
        String id,
        String template,
        String displayName,
        double cost,
        double upkeep,
        double hammerCost,
        double hammersPerHourBonus,
        double beakersPerHour,
        double moneyPerHour,
        int happiness,
        int maxHitpoints,
        String requiredTechnology,
        boolean strategic
) {
}
