package com.avrgaming.civcraft.modern.domain;

import java.util.UUID;

public record TownRecord(
        long id,
        String name,
        long civId,
        UUID mayorUuid,
        String world,
        int x,
        int y,
        int z,
        int level,
        long coins,
        double hammersPerHour,
        double beakersPerHour,
        double moneyPerHour,
        double happiness,
        String happinessState,
        double productionMultiplier,
        double unhappiness,
        double happinessPercentage,
        double cultureMultiplier
) {
}
