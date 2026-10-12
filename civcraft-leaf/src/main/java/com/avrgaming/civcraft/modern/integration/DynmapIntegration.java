package com.avrgaming.civcraft.modern.integration;

public record DynmapIntegration(boolean enabledInConfig, boolean pluginPresent) {
    public IntegrationStatus status() {
        return new IntegrationStatus("Dynmap", enabledInConfig, pluginPresent, "marker-api", "слои towns/culture/structures сохраняются как marker sets");
    }
}
