package com.avrgaming.civcraft.modern.integration;

public record MmoItemsIntegration(boolean enabledInConfig, boolean pluginPresent) {
    public IntegrationStatus status() {
        return new IntegrationStatus("MMOItems", enabledInConfig, pluginPresent, "api-adapter", "оружие/броня/материалы будут храниться как MMOItems templates + custom stats");
    }
}
