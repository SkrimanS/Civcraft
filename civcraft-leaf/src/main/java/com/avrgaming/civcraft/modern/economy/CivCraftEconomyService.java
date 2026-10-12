package com.avrgaming.civcraft.modern.economy;

import com.avrgaming.civcraft.modern.config.ModernCivCraftSettings;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.SQLException;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class CivCraftEconomyService {
    private final JavaPlugin plugin;
    private final StorageBootstrap storage;
    private ModernCivCraftSettings settings;
    private Economy vaultEconomy;

    public CivCraftEconomyService(JavaPlugin plugin, StorageBootstrap storage, ModernCivCraftSettings settings) {
        this.plugin = plugin;
        this.storage = storage;
        this.settings = settings;
        reload(settings);
    }

    public void reload(ModernCivCraftSettings settings) {
        this.settings = settings;
        this.vaultEconomy = null;
        if (!settings.useVaultBridgeForEconomy()) {
            plugin.getLogger().info("CivCraft economy: local SQLite balance mode (Vault bridge disabled).");
            return;
        }
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Vault")) {
            plugin.getLogger().warning("CivCraft economy: Vault is not enabled; falling back to local SQLite balances.");
            return;
        }
        RegisteredServiceProvider<Economy> registration = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null) {
            plugin.getLogger().warning("CivCraft economy: Vault economy provider is missing; falling back to local SQLite balances.");
            return;
        }
        this.vaultEconomy = registration.getProvider();
        plugin.getLogger().info("CivCraft economy: using " + settings.economyProvider() + " via Vault provider " + vaultEconomy.getName() + " currency=" + settings.economyCurrency() + ".");
    }

    public boolean usesExternalProvider() {
        return vaultEconomy != null;
    }

    public double balance(Player player) throws SQLException {
        if (vaultEconomy != null) {
            return Math.rint(vaultEconomy.getBalance(player));
        }
        return storage.getOrCreateResident(player.getUniqueId(), player.getName(), settings.startingCoins()).coins();
    }

    public void withdraw(Player player, double amount, String reason) throws SQLException {
        validateAmount(amount);
        if (amount == 0.0) {
            return;
        }
        if (vaultEconomy != null) {
            EconomyResponse response = vaultEconomy.withdrawPlayer(player, amount);
            if (!response.transactionSuccess()) {
                throw new SQLException("Economy withdraw failed for " + reason + ": " + response.errorMessage);
            }
            return;
        }
        storage.chargeResident(player.getUniqueId(), amount);
    }

    public void deposit(Player player, double amount, String reason) throws SQLException {
        deposit((OfflinePlayer) player, amount, reason);
    }

    public void deposit(OfflinePlayer player, double amount, String reason) throws SQLException {
        validateAmount(amount);
        if (amount == 0.0) {
            return;
        }
        if (vaultEconomy != null) {
            EconomyResponse response = vaultEconomy.depositPlayer(player, amount);
            if (!response.transactionSuccess()) {
                throw new SQLException("Economy deposit failed for " + reason + ": " + response.errorMessage);
            }
            return;
        }
        storage.depositResident(player.getUniqueId(), amount);
    }

    public String currencyId() {
        return settings.economyCurrency();
    }

    private void validateAmount(double amount) throws SQLException {
        if (amount < 0 || Double.isNaN(amount) || Double.isInfinite(amount) || amount != Math.rint(amount)) {
            throw new SQLException("Economy amount must be a whole non-negative number: " + amount);
        }
    }
}
