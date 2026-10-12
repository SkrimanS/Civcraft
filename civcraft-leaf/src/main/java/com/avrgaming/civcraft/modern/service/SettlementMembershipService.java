package com.avrgaming.civcraft.modern.service;

import com.avrgaming.civcraft.modern.domain.CivRecord;
import com.avrgaming.civcraft.modern.domain.ResidentProfile;
import com.avrgaming.civcraft.modern.domain.TownRecord;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.SQLException;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Simple playable membership rules:
 * - Leaders invite players into a civilization; mayors invite players into
 *   their city. A city invite automatically enrolls in its civilization.
 * - Invitations expire after 24 hours and are accepted exactly once.
 * - A resident can belong to one civilization and at most one city.
 * - Founders and mayors cannot abandon their role without transferring it
 *   first (leader/mayor transfer is reserved for a later stage).
 */
public final class SettlementMembershipService {
    private final JavaPlugin plugin;
    private final CivCraftGameService game;
    private final StorageBootstrap storage;

    public SettlementMembershipService(JavaPlugin plugin, CivCraftGameService game, StorageBootstrap storage) {
        this.plugin = plugin;
        this.game = game;
        this.storage = storage;
    }

    private long invitationLifetimeMillis() {
        int configuredHours = plugin.getConfig().getInt("settlements.invitation-expiration-hours", 24);
        return Math.max(1, Math.min(168, configuredHours)) * 60L * 60L * 1000L;
    }

    public String inviteCivilization(Player leader, Player target) throws SQLException {
        CivRecord civ = requireCiv(leader);
        if (!civ.leaderUuid().equals(leader.getUniqueId())) {
            throw new IllegalArgumentException("Приглашать в цивилизацию может только лидер.");
        }
        ResidentProfile invited = game.resident(target);
        requireFreeOfCamp(invited);
        if (invited.civId() != null) {
            throw new IllegalArgumentException("Игрок уже состоит в цивилизации.");
        }
        storage.issueSettlementInvite(target.getUniqueId(), "CIV", civ.id(),
                leader.getUniqueId(), System.currentTimeMillis() + invitationLifetimeMillis());
        return civ.name();
    }

    public String inviteTown(Player mayor, Player target) throws SQLException {
        TownRecord town = requireTown(mayor);
        if (!town.mayorUuid().equals(mayor.getUniqueId())) {
            throw new IllegalArgumentException("Приглашать в город может только мэр.");
        }
        ResidentProfile invited = game.resident(target);
        requireFreeOfCamp(invited);
        if (invited.townId() != null) {
            throw new IllegalArgumentException("Игрок уже состоит в городе.");
        }
        if (invited.civId() != null && invited.civId() != town.civId()) {
            throw new IllegalArgumentException("Игрок состоит в другой цивилизации.");
        }
        storage.issueSettlementInvite(target.getUniqueId(), "TOWN", town.id(),
                mayor.getUniqueId(), System.currentTimeMillis() + invitationLifetimeMillis());
        return town.name();
    }

    public String joinCivilization(Player player) throws SQLException {
        game.resident(player);
        return storage.acceptSettlementInvite(player.getUniqueId(), "CIV");
    }

    public String joinTown(Player player) throws SQLException {
        game.resident(player);
        return storage.acceptSettlementInvite(player.getUniqueId(), "TOWN");
    }

    public void leaveCivilization(Player player) throws SQLException {
        CivRecord civ = requireCiv(player);
        storage.removeSettlementMember(player.getUniqueId(), "CIV", civ.id());
    }

    public void leaveTown(Player player) throws SQLException {
        TownRecord town = requireTown(player);
        storage.removeSettlementMember(player.getUniqueId(), "TOWN", town.id());
    }

    public void kickCivilization(Player leader, String targetName) throws SQLException {
        CivRecord civ = requireCiv(leader);
        if (!civ.leaderUuid().equals(leader.getUniqueId())) {
            throw new IllegalArgumentException("Исключать жителей может только лидер цивилизации.");
        }
        ResidentProfile target = findTarget(targetName);
        if (target.civId() == null || target.civId() != civ.id()) {
            throw new IllegalArgumentException("Игрок не состоит в вашей цивилизации.");
        }
        storage.removeSettlementMember(target.uuid(), "CIV", civ.id());
    }

    public void kickTown(Player mayor, String targetName) throws SQLException {
        TownRecord town = requireTown(mayor);
        if (!town.mayorUuid().equals(mayor.getUniqueId())) {
            throw new IllegalArgumentException("Исключать жителей может только мэр.");
        }
        ResidentProfile target = findTarget(targetName);
        if (target.townId() == null || target.townId() != town.id()) {
            throw new IllegalArgumentException("Игрок не состоит в вашем городе.");
        }
        storage.removeSettlementMember(target.uuid(), "TOWN", town.id());
    }

    public void transferCivilizationLeadership(Player leader, String targetName) throws SQLException {
        CivRecord civ = requireCiv(leader);
        if (!civ.leaderUuid().equals(leader.getUniqueId())) {
            throw new IllegalArgumentException("Назначить лидера может только текущий лидер.");
        }
        ResidentProfile target = findTarget(targetName);
        if (target.uuid().equals(leader.getUniqueId())) {
            throw new IllegalArgumentException("Вы уже лидер этой цивилизации.");
        }
        if (target.civId() == null || target.civId() != civ.id()) {
            throw new IllegalArgumentException("Новый лидер должен быть участником этой цивилизации.");
        }
        storage.transferCivilizationLeadership(civ.id(), leader.getUniqueId(), target.uuid());
    }

    public void transferTownMayorship(Player mayor, String targetName) throws SQLException {
        TownRecord town = requireTown(mayor);
        if (!town.mayorUuid().equals(mayor.getUniqueId())) {
            throw new IllegalArgumentException("Назначить мэра может только текущий мэр.");
        }
        ResidentProfile target = findTarget(targetName);
        if (target.uuid().equals(mayor.getUniqueId())) {
            throw new IllegalArgumentException("Вы уже мэр этого города.");
        }
        if (target.townId() == null || target.townId() != town.id()) {
            throw new IllegalArgumentException("Новый мэр должен быть жителем этого города.");
        }
        storage.transferTownMayorship(town.id(), mayor.getUniqueId(), target.uuid());
    }

    public List<String> civilizationMembers(Player player) throws SQLException {
        return storage.settlementMemberNames("CIV", requireCiv(player).id());
    }

    public List<String> townMembers(Player player) throws SQLException {
        return storage.settlementMemberNames("TOWN", requireTown(player).id());
    }

    private ResidentProfile findTarget(String name) throws SQLException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Укажите ник игрока.");
        }
        return storage.findResidentByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Игрок ещё не заходил на сервер: " + name));
    }

    private CivRecord requireCiv(Player player) throws SQLException {
        ResidentProfile resident = game.resident(player);
        if (resident.civId() == null) {
            throw new IllegalArgumentException("Вы не состоите в цивилизации.");
        }
        return game.civ(resident).orElseThrow(
                () -> new IllegalArgumentException("Цивилизация больше не существует."));
    }

    private TownRecord requireTown(Player player) throws SQLException {
        ResidentProfile resident = game.resident(player);
        if (resident.townId() == null) {
            throw new IllegalArgumentException("Вы не состоите в городе.");
        }
        return game.town(resident).orElseThrow(
                () -> new IllegalArgumentException("Город больше не существует."));
    }

    private void requireFreeOfCamp(ResidentProfile target) {
        if (target.campId() != null) {
            throw new IllegalArgumentException("Сначала необходимо покинуть лагерь.");
        }
    }
}
