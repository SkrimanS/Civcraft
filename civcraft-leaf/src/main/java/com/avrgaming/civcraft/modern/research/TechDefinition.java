package com.avrgaming.civcraft.modern.research;

import java.util.List;

public record TechDefinition(String id, String name, double beakerCost, double coinCost, int points, List<String> requiredTechs, int era) {
}
