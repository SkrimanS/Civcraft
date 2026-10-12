package com.avrgaming.civcraft.modern.integration;

public record WorldEditIntegration(boolean fawePresent, boolean worldEditPresent) {
    public IntegrationStatus status() {
        if (fawePresent) {
            return new IntegrationStatus("WorldEdit/FAWE", true, true, "fawe", "асинхронные paste/undo для построек предпочтительны через FAWE");
        }
        return new IntegrationStatus("WorldEdit/FAWE", true, worldEditPresent, worldEditPresent ? "worldedit" : "missing", "WorldEdit fallback, FAWE предпочтительнее для больших схем");
    }
}
