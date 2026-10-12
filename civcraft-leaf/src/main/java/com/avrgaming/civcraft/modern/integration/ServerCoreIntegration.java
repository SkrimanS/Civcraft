package com.avrgaming.civcraft.modern.integration;

import java.util.Locale;

public record ServerCoreIntegration(String detectedServerName, boolean requireLeafRuntime, boolean allowPaperCompatibleDevRuntime) {
    public IntegrationStatus status() {
        String normalizedName = detectedServerName.toLowerCase(Locale.ROOT);
        boolean leafDetected = normalizedName.contains("leaf");
        boolean acceptable = leafDetected || allowPaperCompatibleDevRuntime;
        String mode = leafDetected ? "leaf-runtime" : (allowPaperCompatibleDevRuntime ? "paper-compatible-dev" : "missing-leaf");
        String note = leafDetected
                ? "целевое ядро Leaf обнаружено"
                : "Leaf наследует Paper API; Paper-compatible runtime разрешён только для разработки/CI";
        return new IntegrationStatus("Leaf server core", requireLeafRuntime, acceptable, mode, note + " (server='" + detectedServerName + "')");
    }
}
