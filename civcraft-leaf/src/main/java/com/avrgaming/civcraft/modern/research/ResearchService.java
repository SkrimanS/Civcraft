package com.avrgaming.civcraft.modern.research;

import com.avrgaming.civcraft.modern.config.ModernCivCraftSettings;
import com.avrgaming.civcraft.modern.domain.ResidentProfile;
import com.avrgaming.civcraft.modern.economy.CivCraftEconomyService;
import com.avrgaming.civcraft.modern.service.CivCraftGameService;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class ResearchService {
    private final JavaPlugin plugin;
    private final StorageBootstrap storage;
    private final CivCraftGameService game;
    private final CivCraftEconomyService economy;
    private final TechDefinitionService definitions;
    private ModernCivCraftSettings settings;

    public ResearchService(JavaPlugin plugin, StorageBootstrap storage, CivCraftGameService game, ModernCivCraftSettings settings, CivCraftEconomyService economy) {
        this.plugin = plugin;
        this.storage = storage;
        this.game = game;
        this.economy = economy;
        this.settings = settings;
        this.definitions = new TechDefinitionService(plugin, settings);
    }

    public void updateSettings(ModernCivCraftSettings settings) {
        this.settings = settings;
        this.definitions.updateSettings(settings);
    }

    public List<TechDefinition> listTechs(int limit) {
        return definitions.list(limit);
    }

    public Optional<TechDefinition> findTech(String id) {
        return definitions.find(id);
    }

    public Optional<ResearchProgress> progress(ResidentProfile resident) throws SQLException {
        if (resident.civId() == null) {
            return Optional.empty();
        }
        Optional<ResearchProgress> progress = storage.researchProgress(resident.civId(), 0.0);
        if (progress.isEmpty()) {
            return Optional.empty();
        }
        TechDefinition definition = definitions.find(progress.get().techId()).orElse(null);
        if (definition == null) {
            return progress;
        }
        return Optional.of(new ResearchProgress(progress.get().civId(), progress.get().techId(), progress.get().progress(), definition.beakerCost()));
    }

    public TechDefinition startResearch(Player player, String techId) throws SQLException {
        ResidentProfile resident = game.resident(player);
        if (resident.civId() == null) {
            throw new IllegalArgumentException("Сначала вступите в цивилизацию или создайте её.");
        }
        TechDefinition definition = definitions.find(techId)
                .orElseThrow(() -> new IllegalArgumentException("Технология не найдена: " + techId));
        long civId = resident.civId();
        Set<String> researched = normalized(storage.researchedTechs(civId));
        if (researched.contains(definition.id().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Эта технология уже изучена.");
        }
        Optional<ResearchProgress> active = storage.researchProgress(civId, definition.beakerCost());
        if (active.isPresent()) {
            throw new IllegalArgumentException("У цивилизации уже идёт исследование: " + active.get().techId());
        }
        for (String required : definition.requiredTechs()) {
            if (!researched.contains(required.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Не изучена обязательная технология: " + required);
            }
        }
        double beakersPerHour = storage.civBeakersPerHour(civId);
        if (beakersPerHour <= 0.0) {
            throw new IllegalArgumentException("У цивилизации нет производства колбочек.");
        }
        economy.withdraw(player, definition.coinCost(), "research start " + definition.id());
        try {
            storage.startResearch(civId, definition.id());
        } catch (SQLException | RuntimeException exception) {
            economy.deposit(player, definition.coinCost(), "research start refund " + definition.id());
            throw exception;
        }
        return definition;
    }

    public void tick() {
        if (!storage.isReady()) {
            return;
        }
        try {
            for (ActiveResearch research : storage.activeResearches()) {
                TechDefinition definition = definitions.find(research.techId()).orElse(null);
                if (definition == null) {
                    plugin.getLogger().warning("Skipping unknown active CivCraft research: " + research.techId());
                    continue;
                }
                double beakersPerHour = storage.civBeakersPerHour(research.civId());
                if (beakersPerHour <= 0.0) {
                    continue;
                }
                double beakersThisMinute = beakersPerHour / 60.0;
                boolean completed = storage.addResearchBeakers(research.civId(), research.techId(), beakersThisMinute, definition.beakerCost());
                if (completed) {
                    plugin.getLogger().info("Civilization #" + research.civId() + " completed technology " + definition.id());
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("CivCraft research tick failed: " + exception.getMessage());
        }
    }

    private Set<String> normalized(Set<String> values) {
        return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
    }
}
