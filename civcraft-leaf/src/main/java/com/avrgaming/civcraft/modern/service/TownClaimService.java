package com.avrgaming.civcraft.modern.service;

import com.avrgaming.civcraft.modern.config.ModernCivCraftSettings;
import com.avrgaming.civcraft.modern.domain.ResidentProfile;
import com.avrgaming.civcraft.modern.domain.TownClaimRecord;
import com.avrgaming.civcraft.modern.domain.TownRecord;
import com.avrgaming.civcraft.modern.economy.CivCraftEconomyService;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.SQLException;
import java.util.Optional;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class TownClaimService {
    private final StorageBootstrap storage;
    private final CivCraftGameService game;
    private final CivCraftEconomyService economy;
    private ModernCivCraftSettings settings;

    public TownClaimService(StorageBootstrap storage, CivCraftGameService game, ModernCivCraftSettings settings, CivCraftEconomyService economy) {
        this.storage = storage;
        this.game = game;
        this.economy = economy;
        this.settings = settings;
    }

    public void updateSettings(ModernCivCraftSettings settings) {
        this.settings = settings;
    }

    public TownClaimRecord claim(Player player) throws SQLException {
        ResidentProfile resident = game.resident(player);
        TownRecord town = requireTown(resident);
        requireMayor(player, town);
        Chunk chunk = player.getLocation().getChunk();
        validateClaimLocation(town, player.getLocation(), chunk);
        // Reject an already-claimed chunk before touching the player's money.
        // The transactional storage check below still protects against races.
        if (storage.findTownClaim(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()).isPresent()) {
            throw new IllegalArgumentException("Этот чанк уже приватизирован.");
        }
        int count = storage.townClaimCount(town.id());
        if (count >= settings.maxTownClaims()) {
            throw new IllegalArgumentException("Город достиг лимита клаймов: " + settings.maxTownClaims());
        }
        economy.withdraw(player, settings.townClaimCost(), "town claim");
        try {
            return storage.claimTownChunk(player.getUniqueId(), town.id(), town.civId(), chunk.getWorld().getName(), chunk.getX(), chunk.getZ(), 0.0);
        } catch (SQLException | RuntimeException exception) {
            economy.deposit(player, settings.townClaimCost(), "town claim refund");
            throw exception;
        }
    }

    public void unclaim(Player player) throws SQLException {
        ResidentProfile resident = game.resident(player);
        TownRecord town = requireTown(resident);
        requireMayor(player, town);
        Chunk chunk = player.getLocation().getChunk();
        TownClaimRecord existing = storage.findTownClaim(chunk.getWorld().getName(), chunk.getX(), chunk.getZ())
                .orElseThrow(() -> new IllegalArgumentException("Этот чанк не приватизирован."));
        if (existing.townId() != town.id()) {
            throw new IllegalArgumentException("Этот чанк принадлежит другому городу.");
        }
        storage.unclaimTownChunk(town.id(), chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
    }

    public Optional<TownClaimRecord> claimAt(Location location) throws SQLException {
        Chunk chunk = location.getChunk();
        return storage.findTownClaim(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
    }

    public int claimCount(Player player) throws SQLException {
        ResidentProfile resident = game.resident(player);
        TownRecord town = requireTown(resident);
        return storage.townClaimCount(town.id());
    }

    public boolean canBuild(Player player, TownClaimRecord claim) throws SQLException {
        if (player.hasPermission("civcraft.admin")) {
            return true;
        }
        ResidentProfile resident = game.resident(player);
        return resident.civId() != null && resident.civId() == claim.civId();
    }

    private void requireMayor(Player player, TownRecord town) {
        if (!town.mayorUuid().equals(player.getUniqueId()) && !player.hasPermission("civcraft.admin")) {
            throw new IllegalArgumentException("Управлять приватами города может только мэр.");
        }
    }

    private TownRecord requireTown(ResidentProfile resident) throws SQLException {
        if (resident.townId() == null) {
            throw new IllegalArgumentException("Сначала создайте город или вступите в него.");
        }
        return game.town(resident).orElseThrow(() -> new IllegalArgumentException("Город не найден в базе данных."));
    }

    private void validateClaimLocation(TownRecord town, Location location, Chunk chunk) {
        if (!town.world().equals(location.getWorld().getName())) {
            throw new IllegalArgumentException("Клайм должен быть в том же мире, что и город.");
        }
        int townChunkX = Math.floorDiv(town.x(), 16);
        int townChunkZ = Math.floorDiv(town.z(), 16);
        int distance = Math.max(Math.abs(chunk.getX() - townChunkX), Math.abs(chunk.getZ() - townChunkZ));
        if (distance > settings.maxTownClaimDistanceChunks()) {
            throw new IllegalArgumentException("Клайм слишком далеко от центра города. Максимум чанков: " + settings.maxTownClaimDistanceChunks());
        }
    }
}
