package com.avrgaming.civcraft.modern.integration;

public record MythicMobsIntegration(boolean enabledInConfig, boolean pluginPresent) {
    public IntegrationStatus status() {
        return new IntegrationStatus("MythicMobs", enabledInConfig, pluginPresent, "api-adapter", "кастомные мобы будут спавниться через MythicBukkit mob manager");
    }
}
