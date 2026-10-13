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
    private com.avrgaming.civcraft.modern.town.TownCatalog townLevels;
    private int minimumCivilizationDistance=15;
    public void useLevels(com.avrgaming.civcraft.modern.town.TownCatalog levels,int distance)throws SQLException {
        if(distance<0)throw new IllegalArgumentException("Invalid civilization land distance");
        this.townLevels=levels;minimumCivilizationDistance=distance;
        storage.transaction(db->{com.avrgaming.civcraft.modern.town.TownLimitsStore.install(db);com.avrgaming.civcraft.modern.town.TownLimitsStore.seed(db,System.currentTimeMillis());return null;});
    }

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
        Chunk chunk = player.getLocation().getChunk();
        var key=new com.avrgaming.civcraft.modern.town.PlotStore.Key(chunk.getWorld().getName(),chunk.getX(),chunk.getZ());
        long now=System.currentTimeMillis();
        long cost=storage.transaction(db->com.avrgaming.civcraft.modern.town.TownLimitsStore.claim(db,town.id(),player.getUniqueId(),key,townLevels,minimumCivilizationDistance,now));
        player.sendMessage(net.kyori.adventure.text.Component.text("Из казны города списано "+cost+" монет."));
        return new TownClaimRecord(key.world(),key.x(),key.z(),town.id(),town.civId(),player.getUniqueId(),now);
    }

    public void unclaim(Player player) throws SQLException {
        ResidentProfile resident = game.resident(player);
        TownRecord town = requireTown(resident);
        Chunk chunk = player.getLocation().getChunk();
        TownClaimRecord existing = storage.findTownClaim(chunk.getWorld().getName(), chunk.getX(), chunk.getZ())
                .orElseThrow(() -> new IllegalArgumentException("Этот чанк не приватизирован."));
        if (existing.townId() != town.id()) {
            throw new IllegalArgumentException("Этот чанк принадлежит другому городу.");
        }
        var key=new com.avrgaming.civcraft.modern.town.PlotStore.Key(chunk.getWorld().getName(),chunk.getX(),chunk.getZ());
        storage.transaction(db->{com.avrgaming.civcraft.modern.town.TownLimitsStore.unclaim(db,town.id(),player.getUniqueId(),key);return null;});
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
        return canAccess(player,claim,"build");
    }
    public boolean canAccess(Player player,TownClaimRecord claim,String action)throws SQLException {
        if (player.hasPermission("civcraft.admin")) {
            return true;
        }
        return storage.query(db->com.avrgaming.civcraft.modern.town.PlotStore.allowed(db,
                new com.avrgaming.civcraft.modern.town.PlotStore.Key(claim.world(),claim.chunkX(),claim.chunkZ()),player.getUniqueId(),action));
    }

    private void requireMayor(Player player, TownRecord town) {
        if (!town.mayorUuid().equals(player.getUniqueId()) && !player.hasPermission("civcraft.admin")) {
            throw new IllegalArgumentException("Управлять приватами города может только мэр.");
        }
    }
    public boolean sameOwner(TownClaimRecord first,TownClaimRecord second)throws SQLException {
        if(first.townId()!=second.townId())return false;
        return storage.query(db->{
            var a=com.avrgaming.civcraft.modern.town.PlotStore.find(db,new com.avrgaming.civcraft.modern.town.PlotStore.Key(first.world(),first.chunkX(),first.chunkZ()));
            var b=com.avrgaming.civcraft.modern.town.PlotStore.find(db,new com.avrgaming.civcraft.modern.town.PlotStore.Key(second.world(),second.chunkX(),second.chunkZ()));
            return java.util.Objects.equals(a.owner(),b.owner());
        });
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
