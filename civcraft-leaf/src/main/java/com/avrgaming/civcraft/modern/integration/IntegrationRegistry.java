package com.avrgaming.civcraft.modern.integration;

import com.avrgaming.civcraft.modern.config.ModernCivCraftSettings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class IntegrationRegistry {
    private final JavaPlugin plugin;
    private final ModernCivCraftSettings settings;
    private final List<IntegrationStatus> statuses = new ArrayList<>();

    public IntegrationRegistry(JavaPlugin plugin, ModernCivCraftSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public void detect() {
        statuses.clear();
        PluginManager plugins = plugin.getServer().getPluginManager();

        statuses.add(new ServerCoreIntegration(plugin.getServer().getName(), settings.requireLeafRuntime(), settings.allowPaperCompatibleDevRuntime()).status());
        statuses.add(new MythicMobsIntegration(settings.mythicMobsEnabled(), plugins.isPluginEnabled("MythicMobs")).status());
        statuses.add(new MmoItemsIntegration(settings.mmoItemsEnabled(), plugins.isPluginEnabled("MMOItems")).status());
        statuses.add(new DynmapIntegration(settings.dynmapEnabled(), plugins.isPluginEnabled("dynmap")).status());
        statuses.add(new ExcellentEconomyIntegration(
                settings.economyProvider().equalsIgnoreCase("ExcellentEconomy"),
                plugins.isPluginEnabled("ExcellentEconomy"),
                settings.economyCurrency(),
                settings.useVaultBridgeForEconomy(),
                plugins.isPluginEnabled("Vault")
        ).status());
        statuses.add(new IntegrationStatus("PlaceholderAPI", settings.placeholderApiEnabled(), plugins.isPluginEnabled("PlaceholderAPI"), "direct-expansion", "плейсхолдеры регистрируются отдельной expansion"));
        statuses.add(new WorldEditIntegration(plugins.isPluginEnabled("FastAsyncWorldEdit"), plugins.isPluginEnabled("WorldEdit")).status());
        statuses.add(new IntegrationStatus("PacketEvents", settings.packetEventsEnabled(), plugins.isPluginEnabled("packetevents") || plugins.isPluginEnabled("PacketEvents"), "reserved", "включать только для packet-only функций"));
        statuses.add(new IntegrationStatus("FancyNpcs", true, plugins.isPluginEnabled("FancyNpcs"), "camp-npc-menus", "NPC лагерей создаются через FancyNpcs"));
        statuses.add(new IntegrationStatus("ItemsAdder", false, plugins.isPluginEnabled("ItemsAdder"), "optional-gui-items", "опционально для предметов GUI через namespaced id"));
    }

    public List<IntegrationStatus> statuses() {
        return Collections.unmodifiableList(statuses);
    }

    public List<String> statusLines() {
        return statuses.stream().map(IntegrationStatus::asMiniMessageLine).toList();
    }
}
