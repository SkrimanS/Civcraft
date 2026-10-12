package com.avrgaming.civcraft.modern.integration;

public record ExcellentEconomyIntegration(boolean enabledInConfig, boolean pluginPresent, String currencyId, boolean vaultBridgeAllowed, boolean vaultPresent) {
    public IntegrationStatus status() {
        String mode = pluginPresent ? "direct-or-service" : (vaultBridgeAllowed && vaultPresent ? "vault-bridge" : "missing");
        String note = pluginPresent
                ? "ExcellentEconomy должен выставить Vault currency=" + currencyId + "; прямой API можно подключить позже за этим адаптером"
                : "нужен ExcellentEconomy или разрешённый Vault bridge для currency=" + currencyId;
        return new IntegrationStatus("ExcellentEconomy", enabledInConfig, pluginPresent || (vaultBridgeAllowed && vaultPresent), mode, note);
    }
}
