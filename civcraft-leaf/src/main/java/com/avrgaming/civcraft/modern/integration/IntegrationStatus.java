package com.avrgaming.civcraft.modern.integration;

public record IntegrationStatus(String name, boolean enabledInConfig, boolean pluginPresent, String mode, String note) {
    public String asMiniMessageLine() {
        String color = pluginPresent ? "<green>" : "<red>";
        String state = pluginPresent ? "найден" : "не найден";
        return color + name + ": " + state + "</" + (pluginPresent ? "green" : "red") + ">"
                + " <gray>config=" + enabledInConfig + ", mode=" + mode + ", " + note + "</gray>";
    }
}
