package com.avrgaming.civcraft.modern.service;

import com.avrgaming.civcraft.modern.config.ModernCivCraftSettings;
import com.avrgaming.civcraft.modern.domain.CampRecord;
import com.avrgaming.civcraft.modern.domain.CivRecord;
import com.avrgaming.civcraft.modern.domain.ResidentProfile;
import com.avrgaming.civcraft.modern.domain.TownRecord;
import com.avrgaming.civcraft.modern.economy.CivCraftEconomyService;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.SQLException;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class CivCraftGameService {
    private final StorageBootstrap storage;
    private final CivCraftEconomyService economy;
    private ModernCivCraftSettings settings;

    public CivCraftGameService(StorageBootstrap storage, ModernCivCraftSettings settings, CivCraftEconomyService economy) {
        this.storage = storage;
        this.settings = settings;
        this.economy = economy;
    }

    public void updateSettings(ModernCivCraftSettings settings) {
        this.settings = settings;
    }

    public ResidentProfile resident(Player player) throws SQLException {
        return storage.getOrCreateResident(player.getUniqueId(), player.getName(), settings.startingCoins());
    }

    public Optional<CivRecord> civ(ResidentProfile resident) throws SQLException {
        return resident.civId() == null ? Optional.empty() : storage.findCiv(resident.civId());
    }

    public Optional<TownRecord> town(ResidentProfile resident) throws SQLException {
        return resident.townId() == null ? Optional.empty() : storage.findTown(resident.townId());
    }

    public Optional<CampRecord> camp(ResidentProfile resident) throws SQLException {
        return storage.findCampByOwner(resident.uuid());
    }

    public TownRecord depositTown(Player player, double amount) throws SQLException {
        ResidentProfile resident = resident(player);
        TownRecord town = requireTown(resident);
        economy.withdraw(player, amount, "town deposit");
        try {
            storage.depositTown(town.id(), amount);
            return storage.findTown(town.id()).orElseThrow();
        } catch (SQLException | RuntimeException exception) {
            economy.deposit(player, amount, "town deposit refund");
            throw exception;
        }
    }

    public TownRecord withdrawTown(Player player, double amount) throws SQLException {
        ResidentProfile resident = resident(player);
        TownRecord town = requireTown(resident);
        if (!town.mayorUuid().equals(player.getUniqueId())) {
            throw new IllegalArgumentException("Только мэр может выводить деньги из банка города.");
        }
        storage.withdrawTown(town.id(), amount);
        try {
            economy.deposit(player, amount, "town withdraw");
            return storage.findTown(town.id()).orElseThrow();
        } catch (SQLException | RuntimeException exception) {
            storage.depositTown(town.id(), amount);
            throw exception;
        }
    }

    public CivRecord depositCiv(Player player, double amount) throws SQLException {
        ResidentProfile resident = resident(player);
        CivRecord civ = requireCiv(resident);
        economy.withdraw(player, amount, "civilization deposit");
        try {
            storage.depositCiv(civ.id(), amount);
            return storage.findCiv(civ.id()).orElseThrow();
        } catch (SQLException | RuntimeException exception) {
            economy.deposit(player, amount, "civilization deposit refund");
            throw exception;
        }
    }

    public CivRecord withdrawCiv(Player player, double amount) throws SQLException {
        ResidentProfile resident = resident(player);
        CivRecord civ = requireCiv(resident);
        if (!civ.leaderUuid().equals(player.getUniqueId())) {
            throw new IllegalArgumentException("Только лидер может выводить деньги из банка цивилизации.");
        }
        storage.withdrawCiv(civ.id(), amount);
        try {
            economy.deposit(player, amount, "civilization withdraw");
            return storage.findCiv(civ.id()).orElseThrow();
        } catch (SQLException | RuntimeException exception) {
            storage.depositCiv(civ.id(), amount);
            throw exception;
        }
    }

    private TownRecord requireTown(ResidentProfile resident) throws SQLException {
        if (resident.townId() == null) {
            throw new IllegalArgumentException("Сначала создайте город или вступите в него.");
        }
        return storage.findTown(resident.townId()).orElseThrow(() -> new IllegalArgumentException("Город не найден."));
    }

    private CivRecord requireCiv(ResidentProfile resident) throws SQLException {
        if (resident.civId() == null) {
            throw new IllegalArgumentException("Сначала создайте цивилизацию или вступите в неё.");
        }
        return storage.findCiv(resident.civId()).orElseThrow(() -> new IllegalArgumentException("Цивилизация не найдена."));
    }

    public CampRecord createCamp(Player player, String name) throws SQLException, IllegalArgumentException {
        return createCampAt(player, name, player.getLocation());
    }

    public CampRecord createCampAt(Player player, String name, Location location) throws SQLException, IllegalArgumentException {
        if (!Validation.isValidFoundationName(name)) {
            throw new IllegalArgumentException("Название лагеря должно быть от 5 до 16 символов и без спецсимволов.");
        }
        ResidentProfile resident = resident(player);
        if (resident.civId() != null) {
            throw new IllegalArgumentException("Игрок в цивилизации не может основать лагерь.");
        }
        if (resident.campId() != null || storage.findCampByOwner(player.getUniqueId()).isPresent()) {
            throw new IllegalArgumentException("У вас уже есть лагерь.");
        }
        economy.withdraw(player, settings.campCost(), "camp create");
        try {
            return storage.createCamp(player.getUniqueId(), name, location, 0.0, settings.campHitpoints(), settings.campFirepointsHours());
        } catch (SQLException | RuntimeException exception) {
            economy.deposit(player, settings.campCost(), "camp create refund");
            throw exception;
        }
    }

    public CivRecord createCivilization(Player player, String name) throws SQLException, IllegalArgumentException {
        return createCivilization(player, name, "");
    }

    public CivRecord createCivilization(Player player, String name, String tag) throws SQLException, IllegalArgumentException {
        if (!Validation.isValidFoundationName(name)) {
            throw new IllegalArgumentException("Название цивилизации должно быть от 5 до 16 символов и без спецсимволов.");
        }
        if (!tag.isBlank() && !Validation.isValidCivTag(tag)) {
            throw new IllegalArgumentException("Тег цивилизации должен быть от 3 до 5 символов и без спецсимволов.");
        }
        ResidentProfile resident = resident(player);
        if (resident.campId() != null) {
            throw new IllegalArgumentException("Игрок в лагере не может основать цивилизацию.");
        }
        if (resident.civId() != null) {
            throw new IllegalArgumentException("Вы уже состоите в цивилизации.");
        }
        economy.withdraw(player, settings.civCost(), "civilization create");
        try {
            return storage.createCivilization(player.getUniqueId(), name, tag, 0.0, settings.startingGovernment());
        } catch (SQLException | RuntimeException exception) {
            economy.deposit(player, settings.civCost(), "civilization create refund");
            throw exception;
        }
    }

    public TownRecord createTown(Player player, String name) throws SQLException, IllegalArgumentException {
        return createTownAt(player, name, player.getLocation());
    }

    public TownRecord createTownAt(Player player, String name, Location location) throws SQLException, IllegalArgumentException {
        if (!Validation.isValidFoundationName(name)) {
            throw new IllegalArgumentException("Название города должно быть от 5 до 16 символов и без спецсимволов.");
        }
        ResidentProfile resident = resident(player);
        if (resident.civId() == null) {
            throw new IllegalArgumentException("Сначала создайте или вступите в цивилизацию.");
        }
        if (resident.townId() != null) {
            throw new IllegalArgumentException("У вас уже есть город.");
        }
        if (storage.hasTownNear(location, settings.minTownDistance())) {
            throw new IllegalArgumentException("Слишком близко к другому городу. Минимальная дистанция: " + settings.minTownDistance());
        }
        economy.withdraw(player, settings.townCost(), "town create");
        try {
            return storage.createTown(player.getUniqueId(), resident.civId(), name, location, 0.0, settings.townHammersPerHour());
        } catch (SQLException | RuntimeException exception) {
            economy.deposit(player, settings.townCost(), "town create refund");
            throw exception;
        }
    }


    public void rollbackCreatedCamp(Player player, CampRecord camp) throws SQLException {
        if (camp == null) {
            return;
        }
        storage.deleteCamp(camp.id(), player.getUniqueId());
    }

    public void rollbackCreatedTown(Player player, TownRecord town) throws SQLException {
        if (town == null) {
            return;
        }
        storage.deleteTown(town.id(), player.getUniqueId());
    }

    public void rollbackCreatedCivilization(Player player, CivRecord civ) throws SQLException {
        if (civ == null) {
            return;
        }
        storage.deleteCivilization(civ.id(), player.getUniqueId());
    }

}
