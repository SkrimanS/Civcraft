package com.avrgaming.civcraft.modern.integration;

public record TypewriterIntegration(boolean enabledInConfig, boolean pluginPresent) {
    public IntegrationStatus status() {
        return new IntegrationStatus("Typewriter", enabledInConfig, pluginPresent, "future-event-bridge", "зарезервирован доменный event bus для квестов/диалогов");
    }
}
