package com.avrgaming.civcraft.modern.camp;

import com.avrgaming.civcraft.modern.config.ModernCivCraftSettings;
import com.avrgaming.civcraft.modern.domain.ResidentProfile;
import com.avrgaming.civcraft.modern.economy.CivCraftEconomyService;
import com.avrgaming.civcraft.modern.service.CivCraftGameService;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import java.time.Duration;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

public final class CampService {
    private static final String ROLE_LEADER = "LEADER";
    private static final String ROLE_ELDER = "ELDER";
    private static final String ROLE_MEMBER = "MEMBER";
    private static final long TP_COOLDOWN_MILLIS = 30L * 60L * 1000L;
    private static final long PLANT_GROW_MILLIS = 30L * 60L * 1000L;
    private static final long CONTROL_ATTACK_DELAY_MILLIS = 22L * 60L * 60L * 1000L;
    private static final int CONTROL_MAX_HP = 50;
    private static final String CONTROL_HOLOGRAM_PREFIX = "§cКонтрольный блок: ";

    private final JavaPlugin plugin;
    private final CivCraftGameService game;
    private final CivCraftEconomyService economy;
    private ModernCivCraftSettings settings;
    private final Map<UUID, PendingInvite> invites = new ConcurrentHashMap<>();
    private final Map<UUID, Long> teleportCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> disbandConfirmations = new ConcurrentHashMap<>();
    private final Map<String, Long> cropStartTimes = new ConcurrentHashMap<>();
    private volatile YamlConfiguration messages;
    private final Random random = new Random();

    public CampService(JavaPlugin plugin, CivCraftGameService game, CivCraftEconomyService economy, ModernCivCraftSettings settings) {
        this.plugin = plugin;
        this.game = game;
        this.economy = economy;
        this.settings = settings;
        reloadMessages();
        initializeStorage();
    }

    public void updateSettings(ModernCivCraftSettings settings) {
        this.settings = settings;
        reloadMessages();
    }

    public void startTasks() {
        plugin.getServer().getScheduler().runTask(plugin, this::setupMissingWorkstations);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::setupMissingWorkstations, 20L, 20L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickBreaker, 20L * 8L, 20L * 8L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickFarmGrowth, 20L * 60L, 20L * 60L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickCampProduction, 20L * 60L * 60L, 20L * 60L * 60L);
    }

    public boolean hasPendingDisband(Player player) {
        Long expires = disbandConfirmations.get(player.getUniqueId());
        return expires != null && expires > System.currentTimeMillis();
    }

    public boolean handleDisbandChat(Player player, String message) {
        if (!hasPendingDisband(player)) {
            return false;
        }
        String normalized = message.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("yes") && !normalized.equals("no")) {
            player.sendMessage(message("camp.disband.confirm-again", "Напишите yes для роспуска лагеря или no для отмены."));
            return true;
        }
        disbandConfirmations.remove(player.getUniqueId());
        if (normalized.equals("no")) {
            player.sendMessage(message("camp.disband.cancelled", "Роспуск лагеря отменен."));
            return true;
        }
        try {
            disband(player);
        } catch (SQLException | IllegalArgumentException exception) {
            player.sendMessage(message("camp.error-format", "Ошибка: {error}").replace("{error}", exception.getMessage()));
        }
        return true;
    }

    public boolean handleCommand(Player player, String[] args) throws SQLException {
        if (args.length <= 1) {
            help(player);
            return true;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> info(player);
            case "promote" -> promote(player, arg(args, 2, message("camp.error.specify-player", "Укажите ник игрока.")));
            case "lower" -> lower(player, arg(args, 2, message("camp.error.specify-player", "Укажите ник игрока.")));
            case "setleader" -> setLeader(player, arg(args, 2, message("camp.error.specify-player", "Укажите ник игрока.")));
            case "disband" -> requestDisband(player);
            case "invite" -> invite(player, arg(args, 2, message("camp.error.specify-player", "Укажите ник игрока.")));
            case "kick" -> kick(player, arg(args, 2, message("camp.error.specify-player", "Укажите ник игрока.")));
            case "leave" -> leave(player);
            case "tp", "teleport" -> teleport(player);
            case "accept" -> acceptInvite(player);
            case "decline", "deny" -> declineInvite(player);
            case "upgrade" -> upgrade(player, arg(args, 2, message("camp.error.specify-upgrade", "Укажите улучшение: farm или breaker.")));
            default -> help(player);
        }
        return true;
    }

    public List<String> completions(int argIndex) {
        if (argIndex == 1) {
            return List.of("info", "invite", "kick", "promote", "lower", "setleader", "disband", "leave", "tp", "teleport", "upgrade", "accept", "decline");
        }
        if (argIndex == 2) {
            return List.of("farm", "breaker");
        }
        return List.of();
    }

    private void help(Player player) {
        player.sendMessage(message("camp.help.header", "§6Команды лагеря:"));
        player.sendMessage(message("camp.help.info", "§e/c info §7- информация о лагере"));
        player.sendMessage(message("camp.help.invite", "§e/c invite <ник> §7- пригласить игрока"));
        player.sendMessage(message("camp.help.kick", "§e/c kick <ник> §7- кикнуть игрока"));
        player.sendMessage(message("camp.help.promote", "§e/c promote <ник> §7- повысить участника до старосты"));
        player.sendMessage(message("camp.help.lower", "§e/c lower <ник> §7- понизить старосту до участника"));
        player.sendMessage(message("camp.help.setleader", "§e/c setleader <ник> §7- передать главу лагеря"));
        player.sendMessage(message("camp.help.disband", "§e/c disband §7- распустить лагерь"));
        player.sendMessage(message("camp.help.leave", "§e/c leave §7- выйти из лагеря"));
        player.sendMessage(message("camp.help.tp", "§e/c tp §7- телепортироваться в лагерь, кд 30 минут"));
        player.sendMessage(message("camp.help.upgrade", "§e/c upgrade farm|breaker §7- купить улучшение за 2500 монет"));
    }

    private void info(Player player) throws SQLException {
        CampMember self = requireCampMember(player.getUniqueId());
        CampInfo camp = campInfo(self.campId()).orElseThrow(() -> new IllegalArgumentException(message("camp.error.camp-not-found", "Лагерь не найден.")));
        List<CampMember> members = members(camp.id());
        List<String> elders = members.stream().filter(m -> ROLE_ELDER.equals(m.role())).map(CampMember::name).toList();
        List<String> normal = members.stream().filter(m -> ROLE_MEMBER.equals(m.role())).map(CampMember::name).toList();
        player.sendMessage(msg("camp.info.header", "§6Лагерь: §e{name}", "{name}", camp.name()));
        int membersCount = memberCount(camp.id());
        player.sendMessage(msg("camp.info.leader", "§7Главарь: §e{leader}", "{leader}", playerName(camp.leaderUuid())));
        player.sendMessage(msg("camp.info.elders", "§7Старосты: §e{elders}", "{elders}", elders.isEmpty() ? message("camp.common.none", "-") : String.join(", ", elders)));
        player.sendMessage(msg("camp.info.members", "§7Участники: §e{members}", "{members}", normal.isEmpty() ? message("camp.common.none", "-") : String.join(", ", normal)));
        player.sendMessage(msg("camp.info.level", "§7Уровень: §e{level}§7, опыт: §e{experience}/{next}", "{level}", String.valueOf(camp.level()), "{experience}", String.valueOf(camp.experience()), "{next}", String.valueOf(nextLevelXp(camp.level()))));
        player.sendMessage(msg("camp.info.bread", "§7Хлеб в час: §e{bread}§7, жителей: §e{members}", "{bread}", String.valueOf(breadRequired(camp.level(), membersCount)), "{members}", String.valueOf(membersCount)));
        player.sendMessage(msg("camp.info.control", "§7Контрольный блок: §e{hp}/{max_hp} HP§7, рейд: §e{raid}", "{hp}", String.valueOf(camp.controlHp()), "{max_hp}", String.valueOf(CONTROL_MAX_HP), "{raid}", raidStatus(camp)));
        player.sendMessage(msg("camp.info.leadership", "§7Очки лидерства главаря: §e{points}/24", "{points}", String.valueOf(leadershipPoints(camp.leaderUuid()))));
        player.sendMessage(msg("camp.info.upgrades", "§7Улучшения: farm={farm}, breaker={breaker}", "{farm}", yesNo(camp.farmUpgrade()), "{breaker}", yesNo(camp.breakerUpgrade())));
    }

    private void promote(Player player, String targetName) throws SQLException {
        CampMember actor = requireRole(player.getUniqueId(), ROLE_LEADER);
        CampMember target = requireCampMember(resolvePlayer(targetName));
        requireSameCamp(actor, target);
        if (!ROLE_MEMBER.equals(target.role())) {
            throw new IllegalArgumentException(message("camp.error.promote-member-only", "Повысить можно только участника."));
        }
        setRole(target.uuid(), ROLE_ELDER);
        player.sendMessage(message("camp.promote.success", "Игрок повышен до старосты."));
    }

    private void lower(Player player, String targetName) throws SQLException {
        CampMember actor = requireRole(player.getUniqueId(), ROLE_LEADER);
        CampMember target = requireCampMember(resolvePlayer(targetName));
        requireSameCamp(actor, target);
        if (!ROLE_ELDER.equals(target.role())) {
            throw new IllegalArgumentException(message("camp.error.lower-elder-only", "Понизить можно только старосту."));
        }
        setRole(target.uuid(), ROLE_MEMBER);
        player.sendMessage(message("camp.lower.success", "Игрок понижен до участника."));
    }

    private void setLeader(Player player, String targetName) throws SQLException {
        CampMember actor = requireRole(player.getUniqueId(), ROLE_LEADER);
        CampMember target = requireCampMember(resolvePlayer(targetName));
        requireSameCamp(actor, target);
        connectionalUpdateLeader(actor.campId(), actor.uuid(), target.uuid());
        player.sendMessage(msg("camp.setleader.success", "Глава лагеря передан игроку {player}.", "{player}", target.name()));
    }

    private void invite(Player player, String targetName) throws SQLException {
        CampMember actor = requireAtLeastElder(player.getUniqueId());
        OfflinePlayer offlineTarget = Bukkit.getOfflinePlayer(targetName);
        UUID targetUuid = offlineTarget.getUniqueId();
        Optional<ResidentProfile> existing = findResident(targetUuid);
        if (existing.isPresent() && (existing.get().campId() != null || existing.get().civId() != null)) {
            throw new IllegalArgumentException(message("camp.error.target-already-group", "Игрок уже состоит в лагере или цивилизации."));
        }
        CampInfo camp = campInfo(actor.campId()).orElseThrow();
        invites.put(targetUuid, new PendingInvite(actor.campId(), camp.name(), player.getUniqueId(), System.currentTimeMillis() + 5L * 60L * 1000L));
        Player online = Bukkit.getPlayer(targetUuid);
        if (online != null) {
            online.sendMessage(component(msg("camp.invite.received", "§eВас пригласили в лагерь {camp}", "{camp}", camp.name())));
            online.sendMessage(component(message("camp.invite.accept-button", "&7[&aПринять&7]")).clickEvent(ClickEvent.runCommand("/c accept"))
                    .append(Component.text("  "))
                    .append(component(message("camp.invite.decline-button", "&7[&cОтклонить&7]")).clickEvent(ClickEvent.runCommand("/c decline"))));
        }
        player.sendMessage(message("camp.invite.sent", "Приглашение отправлено."));
    }

    private void acceptInvite(Player player) throws SQLException {
        PendingInvite invite = invites.remove(player.getUniqueId());
        if (invite == null || invite.expiresAt() < System.currentTimeMillis()) {
            throw new IllegalArgumentException(message("camp.error.no-invite", "Активного приглашения нет."));
        }
        ResidentProfile resident = game.resident(player);
        if (resident.campId() != null || resident.civId() != null) {
            throw new IllegalArgumentException(message("camp.error.self-already-group", "Вы уже состоите в лагере или цивилизации."));
        }
        addCampMember(invite.campId(), player.getUniqueId(), player.getName(), ROLE_MEMBER);
        player.sendMessage(msg("camp.invite.accepted", "Вы вступили в лагерь {camp}.", "{camp}", invite.campName()));
    }

    private void declineInvite(Player player) {
        invites.remove(player.getUniqueId());
        player.sendMessage(message("camp.invite.declined", "Приглашение отклонено."));
    }

    private void kick(Player player, String targetName) throws SQLException {
        CampMember actor = requireAtLeastElder(player.getUniqueId());
        CampMember target = requireCampMember(resolvePlayer(targetName));
        requireSameCamp(actor, target);
        if (ROLE_LEADER.equals(target.role())) {
            throw new IllegalArgumentException(message("camp.error.kick-leader", "Главаря нельзя кикнуть."));
        }
        if (ROLE_ELDER.equals(target.role()) && !ROLE_LEADER.equals(actor.role())) {
            throw new IllegalArgumentException(message("camp.error.kick-elder-leader-only", "Старосту может кикнуть только главарь."));
        }
        removeCampMember(target.uuid());
        Player online = Bukkit.getPlayer(target.uuid());
        if (online != null) {
            online.sendMessage(message("camp.kick.target", "Вас исключили из лагеря."));
        }
        player.sendMessage(message("camp.kick.success", "Игрок исключен из лагеря."));
    }

    private void leave(Player player) throws SQLException {
        CampMember self = requireCampMember(player.getUniqueId());
        if (ROLE_LEADER.equals(self.role())) {
            throw new IllegalArgumentException(message("camp.error.leader-cannot-leave", "Главарь не может выйти из лагеря. Передайте главу или распустите лагерь."));
        }
        removeCampMember(player.getUniqueId());
        player.sendMessage(message("camp.leave.success", "Вы вышли из лагеря."));
    }

    private void requestDisband(Player player) throws SQLException {
        requireRole(player.getUniqueId(), ROLE_LEADER);
        disbandConfirmations.put(player.getUniqueId(), System.currentTimeMillis() + 30_000L);
        player.sendMessage(message("camp.disband.confirm", "Напишите yes в чат, чтобы распустить лагерь. Напишите no, чтобы отменить."));
    }

    private void disband(Player player) throws SQLException {
        CampMember self = requireRole(player.getUniqueId(), ROLE_LEADER);
        destroyCamp(self.campId(), true);
        player.sendMessage(message("camp.disband.success", "Лагерь распущен."));
    }

    private void teleport(Player player) throws SQLException {
        CampMember self = requireCampMember(player.getUniqueId());
        long now = System.currentTimeMillis();
        Long last = teleportCooldowns.get(player.getUniqueId());
        if (last != null && now - last < TP_COOLDOWN_MILLIS) {
            long left = (TP_COOLDOWN_MILLIS - (now - last)) / 1000L;
            throw new IllegalArgumentException(msg("camp.error.teleport-cooldown", "Телепорт будет доступен через {minutes}м {seconds}с.", "{minutes}", String.valueOf(left / 60L), "{seconds}", String.valueOf(left % 60L)));
        }
        CampInfo camp = campInfo(self.campId()).orElseThrow();
        World world = Bukkit.getWorld(camp.world());
        if (world == null) {
            throw new IllegalArgumentException(message("camp.error.world-not-found", "Мир лагеря не найден."));
        }
        teleportCooldowns.put(player.getUniqueId(), now);
        player.teleport(new Location(world, camp.x() + 0.5, camp.y(), camp.z() + 0.5));
        player.sendMessage(message("camp.teleport.success", "Телепортация в лагерь."));
    }

    private void upgrade(Player player, String upgrade) throws SQLException {
        CampMember self = requireRole(player.getUniqueId(), ROLE_LEADER);
        String clean = upgrade.toLowerCase(Locale.ROOT);
        if (!clean.equals("farm") && !clean.equals("breaker")) {
            throw new IllegalArgumentException(message("camp.error.invalid-upgrade", "Улучшение должно быть farm или breaker."));
        }
        buyUpgrade(player, self.campId(), clean);
        player.sendMessage(msg("camp.upgrade.success", "Улучшение {upgrade} куплено.", "{upgrade}", clean));
    }

    public boolean isCampStructureChunk(String world, int chunkX, int chunkZ) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM structure_chunks sc
                JOIN structure_builds sb ON sb.id = sc.build_id
                WHERE sc.world = ? AND sc.chunk_x = ? AND sc.chunk_z = ? AND sb.structure_id = ? AND sb.status <> 'DEMOLISHED'
                """)) {
            statement.setString(1, world);
            statement.setInt(2, chunkX);
            statement.setInt(3, chunkZ);
            statement.setString(4, settings.campStructureId());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ignored) {
            return false;
        }
    }

    private void tickCampProduction() {
        try {
            for (CampInfo camp : camps()) {
                CampLevel level = level(camp.level());
                int bread = breadRequired(camp.level(), memberCount(camp.id()));
                if (bread <= 0) {
                    continue;
                }
                Location chest = camp.foodChestLocation();
                if (chest == null || !(chest.getBlock().getState() instanceof Chest foodChest)) {
                    continue;
                }
                if (!removeItems(foodChest.getInventory(), Material.BREAD, bread)) {
                    continue;
                }
                addCampExperienceAndMoney(camp.id(), camp.leaderUuid(), level.experience(), level.money());
                notifyCampProduction(camp.id(), camp.name(), level.experience(), level.money(), bread);
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Camp production tick failed: " + exception.getMessage());
        }
    }

    private void tickBreaker() {
        try {
            for (CampInfo camp : camps()) {
                if (!camp.breakerUpgrade()) {
                    continue;
                }
                Location input = camp.breakerInputLocation();
                Location output = camp.breakerOutputLocation();
                if (input == null || output == null || !(input.getBlock().getState() instanceof Chest inputChest) || !(output.getBlock().getState() instanceof Chest outputChest)) {
                    continue;
                }
                if (countBreakerInputs(inputChest.getInventory()) < 8) {
                    continue;
                }

                List<Material> drops = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    drops.add(randomBreakerDrop());
                }
                if (!canFitItems(outputChest.getInventory(), drops)) {
                    continue;
                }

                for (int i = 0; i < 8; i++) {
                    removeOneBreakerInput(inputChest.getInventory());
                }
                for (Material drop : drops) {
                    outputChest.getInventory().addItem(new ItemStack(drop, 1));
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Camp breaker tick failed: " + exception.getMessage());
        }
    }

    private void tickFarmGrowth() {
        long now = System.currentTimeMillis();
        try {
            for (CampInfo camp : camps()) {
                if (!camp.farmUpgrade()) {
                    continue;
                }
                for (StructureChunk chunk : campChunks(camp.id())) {
                    World world = Bukkit.getWorld(chunk.world());
                    if (world == null) {
                        continue;
                    }
                    Chunk bukkitChunk = world.getChunkAt(chunk.chunkX(), chunk.chunkZ());
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            for (int y = Math.max(1, world.getMinHeight()); y < Math.min(world.getMaxHeight(), 151); y++) {
                                Block block = bukkitChunk.getBlock(x, y, z);
                                if (!(block.getBlockData() instanceof Ageable ageable)) {
                                    continue;
                                }
                                Block soil = block.getRelative(BlockFace.DOWN);
                                if (soil.getType() != Material.FARMLAND || !isCampStructureChunk(world.getName(), block.getChunk().getX(), block.getChunk().getZ())) {
                                    continue;
                                }
                                String key = blockKey(block);
                                long planted = cropStartTimes.computeIfAbsent(key, ignored -> now);
                                if (now - planted >= PLANT_GROW_MILLIS && ageable.getAge() < ageable.getMaximumAge()) {
                                    ageable.setAge(ageable.getMaximumAge());
                                    block.setBlockData(ageable, false);
                                }
                            }
                        }
                    }
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Camp farm tick failed: " + exception.getMessage());
        }
    }

    private void setupMissingWorkstations() {
        try {
            for (CampInfo camp : camps()) {
                boolean needsWorkstations = !camp.hasAllWorkstations();
                boolean needsNpcMarker = !hasCampNpcMarker(camp.id());
                if (needsWorkstations || needsNpcMarker) {
                    Workstations found = scanAndReplaceWorkstations(camp);
                    if (needsWorkstations) {
                        updateWorkstations(camp.id(), found.food(), found.breakerInput(), found.breakerOutput(), found.controlBlock());
                        camp = campInfo(camp.id()).orElse(camp);
                    }
                }
                updateControlHologram(camp);
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Camp workstation setup failed: " + exception.getMessage());
        }
    }

    private Workstations scanAndReplaceWorkstations(CampInfo camp) throws SQLException {
        Location[] found = new Location[] {
                camp.foodChestLocation(),
                camp.breakerInputLocation(),
                camp.breakerOutputLocation(),
                camp.controlBlockLocation()
        };

        scanProtectedCampBlocks(camp, found);
        if (found[0] == null || found[1] == null || found[2] == null || found[3] == null) {
            scanCampChunksForMarkers(camp, found);
        }

        return new Workstations(found[0], found[1], found[2], found[3]);
    }

    private void scanProtectedCampBlocks(CampInfo camp, Location[] found) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT pb.world, pb.x, pb.y, pb.z
                FROM protected_blocks pb
                JOIN structure_builds sb ON sb.id = pb.build_id
                JOIN camps c ON c.owner_uuid = sb.builder_uuid OR (c.world = sb.world AND c.x = sb.x AND c.y = sb.y AND c.z = sb.z)
                WHERE c.id = ?
                  AND sb.structure_id = ?
                  AND sb.status <> 'DEMOLISHED'
                """)) {
            statement.setLong(1, camp.id());
            statement.setString(2, settings.campStructureId());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    World world = Bukkit.getWorld(rs.getString("world"));
                    if (world == null) {
                        continue;
                    }
                    Block block = world.getBlockAt(rs.getInt("x"), rs.getInt("y"), rs.getInt("z"));
                    replaceCampMarkerBlock(camp.id(), block, found);
                    fixCampSign(block);
                }
            }
        }
    }

    private void scanCampChunksForMarkers(CampInfo camp, Location[] found) throws SQLException {
        List<StructureChunk> chunks = campChunks(camp.id());
        if (chunks.isEmpty()) {
            chunks = List.of(new StructureChunk(camp.world(), camp.x() >> 4, camp.z() >> 4));
        }
        for (StructureChunk campChunk : chunks) {
            World world = Bukkit.getWorld(campChunk.world());
            if (world == null) {
                continue;
            }
            int minY = Math.max(world.getMinHeight(), 63);
            int maxY = Math.min(world.getMaxHeight() - 1, 150);
            int minX = campChunk.chunkX() << 4;
            int minZ = campChunk.chunkZ() << 4;
            for (int x = minX; x < minX + 16; x++) {
                for (int z = minZ; z < minZ + 16; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        Block block = world.getBlockAt(x, y, z);
                        Material type = block.getType();
                        if (type == Material.BLACK_CONCRETE
                                || type == Material.BLUE_CONCRETE
                                || type == Material.PURPLE_CONCRETE
                                || type == Material.OBSIDIAN
                                || type == Material.PINK_CONCRETE
                                || block.getState() instanceof Sign) {
                            replaceCampMarkerBlock(camp.id(), block, found);
                            fixCampSign(block);
                        }
                    }
                }
            }
        }
    }

    private void replaceCampMarkerBlock(long campId, Block block, Location[] found) {
        Material type = block.getType();
        if (type == Material.BLACK_CONCRETE) {
            block.setType(Material.CHEST, false);
            if (found[0] == null) {
                found[0] = block.getLocation();
            }
            return;
        }
        if (type == Material.BLUE_CONCRETE) {
            block.setType(Material.CHEST, false);
            if (found[1] == null) {
                found[1] = block.getLocation();
            }
            return;
        }
        if (type == Material.PURPLE_CONCRETE) {
            block.setType(Material.CHEST, false);
            if (found[2] == null) {
                found[2] = block.getLocation();
            }
            return;
        }
        if (type == Material.OBSIDIAN && found[3] == null) {
            found[3] = block.getLocation();
            return;
        }
        if (type == Material.PINK_CONCRETE) {
            saveCampNpcMarker(campId, block);
            block.setType(Material.AIR, false);
        }
    }

    private boolean hasCampNpcMarker(long campId) {
        Object api = campApi();
        if (api == null) {
            return true;
        }
        try {
            Method method = api.getClass().getMethod("hasCampNpcMarker", long.class);
            Object result = method.invoke(api, campId);
            return result instanceof Boolean value && value;
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().warning("Unable to check camp NPC marker through CivCraftCampApi: " + exception.getMessage());
            return true;
        }
    }

    private void saveCampNpcMarker(long campId, Block block) {
        Object api = campApi();
        if (api == null) {
            return;
        }
        try {
            Method method = api.getClass().getMethod("saveCampNpcMarker", long.class, String.class, int.class, int.class, int.class);
            method.invoke(api, campId, block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        } catch (NoSuchMethodException exception) {
            plugin.getLogger().warning("CivCraftCampApi found, but saveCampNpcMarker(long, String, int, int, int) is missing.");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().warning("Unable to save camp NPC marker through CivCraftCampApi: " + exception.getMessage());
        }
    }

    private Object campApi() {
        try {
            Method method = plugin.getClass().getMethod("getCampApi");
            return method.invoke(plugin);
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().warning("Unable to access CivCraftCampApi: " + exception.getMessage());
            return null;
        }
    }

    private void fixCampSign(Block block) {
        if (!(block.getState() instanceof Sign sign)) {
            return;
        }
        try {
            sign.getSide(Side.FRONT).setColor(DyeColor.BLACK);
            sign.getSide(Side.FRONT).setGlowingText(false);
            sign.getSide(Side.BACK).setColor(DyeColor.BLACK);
            sign.getSide(Side.BACK).setGlowingText(false);
            sign.update(true, false);
        } catch (RuntimeException ignored) {
            // Sign API can fail on invalid or partially pasted tile entities. The block itself is kept unchanged.
        }
    }

    private void notifyCampProduction(long campId, String campName, int experience, long money, int bread) {
        try {
            String message = msg("camp.production.notify", "§6Лагерь §e{camp} §6получил §e{experience} §6опыта. Потрачено хлеба: §e{bread}§6. Главарь получил §e{money} §6монет.", "{camp}", campName, "{experience}", String.valueOf(experience), "{bread}", String.valueOf(bread), "{money}", String.valueOf(money));
            for (CampMember member : members(campId)) {
                Player player = Bukkit.getPlayer(member.uuid());
                if (player != null) {
                    player.sendMessage(message);
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to notify camp production: " + exception.getMessage());
        }
    }

    private void initializeStorage() {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS camp_members(camp_id INTEGER NOT NULL, uuid TEXT PRIMARY KEY, name TEXT NOT NULL, role TEXT NOT NULL, joined_at INTEGER NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS camp_leadership_points(uuid TEXT PRIMARY KEY, points INTEGER NOT NULL DEFAULT 0)");
            addColumnIfMissing(connection, "camps", "level", "INTEGER NOT NULL DEFAULT 1");
            addColumnIfMissing(connection, "camps", "experience", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(connection, "camps", "leadership_points", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(connection, "camps", "farm_upgrade", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(connection, "camps", "breaker_upgrade", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(connection, "camps", "food_chest_world", "TEXT");
            addColumnIfMissing(connection, "camps", "food_chest_x", "INTEGER");
            addColumnIfMissing(connection, "camps", "food_chest_y", "INTEGER");
            addColumnIfMissing(connection, "camps", "food_chest_z", "INTEGER");
            addColumnIfMissing(connection, "camps", "breaker_input_world", "TEXT");
            addColumnIfMissing(connection, "camps", "breaker_input_x", "INTEGER");
            addColumnIfMissing(connection, "camps", "breaker_input_y", "INTEGER");
            addColumnIfMissing(connection, "camps", "breaker_input_z", "INTEGER");
            addColumnIfMissing(connection, "camps", "breaker_output_world", "TEXT");
            addColumnIfMissing(connection, "camps", "breaker_output_x", "INTEGER");
            addColumnIfMissing(connection, "camps", "breaker_output_y", "INTEGER");
            addColumnIfMissing(connection, "camps", "breaker_output_z", "INTEGER");
            addColumnIfMissing(connection, "camps", "control_block_world", "TEXT");
            addColumnIfMissing(connection, "camps", "control_block_x", "INTEGER");
            addColumnIfMissing(connection, "camps", "control_block_y", "INTEGER");
            addColumnIfMissing(connection, "camps", "control_block_z", "INTEGER");
            addColumnIfMissing(connection, "camps", "control_hp", "INTEGER NOT NULL DEFAULT 50");
            statement.executeUpdate("INSERT OR IGNORE INTO camp_members(camp_id, uuid, name, role, joined_at) SELECT id, owner_uuid, name, 'LEADER', created_at FROM camps");
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to initialize camp storage: " + exception.getMessage());
        }
    }

    private void addColumnIfMissing(Connection connection, String table, String column, String definition) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(" + table + ")"); ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + settings.sqlitePath(plugin.getDataFolder().toPath()));
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    private Optional<ResidentProfile> findResident(UUID uuid) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM residents WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ResidentProfile(
                        uuid,
                        rs.getString("name"),
                        rs.getLong("coins"),
                        nullableLong(rs, "civ_id"),
                        nullableLong(rs, "town_id"),
                        nullableLong(rs, "camp_id")
                ));
            }
        }
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private CampMember requireCampMember(UUID uuid) throws SQLException {
        Optional<CampMember> member = findCampMember(uuid);
        if (member.isPresent()) {
            return member.get();
        }
        throw new IllegalArgumentException(message("camp.error.not-in-camp", "Вы не состоите в лагере."));
    }

    private CampMember requireRole(UUID uuid, String role) throws SQLException {
        CampMember member = requireCampMember(uuid);
        if (!role.equals(member.role())) {
            throw new IllegalArgumentException(message("camp.error.no-permission", "Недостаточно прав в лагере."));
        }
        return member;
    }

    private CampMember requireAtLeastElder(UUID uuid) throws SQLException {
        CampMember member = requireCampMember(uuid);
        if (!ROLE_LEADER.equals(member.role()) && !ROLE_ELDER.equals(member.role())) {
            throw new IllegalArgumentException(message("camp.error.elder-only", "Команда доступна только главарю или старостам."));
        }
        return member;
    }

    private Optional<CampMember> findCampMember(UUID uuid) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("SELECT cm.*, c.owner_uuid FROM camp_members cm JOIN camps c ON c.id = cm.camp_id WHERE cm.uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapMember(rs));
                }
            }
        }
        Optional<ResidentProfile> resident = findResident(uuid);
        if (resident.isPresent() && resident.get().campId() != null) {
            try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM camps WHERE id = ?")) {
                statement.setLong(1, resident.get().campId());
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        String role = rs.getString("owner_uuid").equals(uuid.toString()) ? ROLE_LEADER : ROLE_MEMBER;
                        addCampMember(resident.get().campId(), uuid, resident.get().name(), role);
                        return Optional.of(new CampMember(resident.get().campId(), uuid, resident.get().name(), role));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private CampMember mapMember(ResultSet rs) throws SQLException {
        return new CampMember(rs.getLong("camp_id"), UUID.fromString(rs.getString("uuid")), rs.getString("name"), rs.getString("role"));
    }

    private void addCampMember(long campId, UUID uuid, String name, String role) throws SQLException {
        Optional<ResidentProfile> resident = findResident(uuid);
        if (resident.isPresent() && resident.get().civId() != null) {
            throw new IllegalArgumentException(message("camp.error.player-in-civ", "Игрок состоит в цивилизации."));
        }
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO camp_members(camp_id, uuid, name, role, joined_at) VALUES(?, ?, ?, ?, ?)")) {
                    statement.setLong(1, campId);
                    statement.setString(2, uuid.toString());
                    statement.setString(3, name);
                    statement.setString(4, role);
                    statement.setLong(5, System.currentTimeMillis());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("UPDATE residents SET camp_id = ?, updated_at = ? WHERE uuid = ?")) {
                    statement.setLong(1, campId);
                    statement.setLong(2, System.currentTimeMillis());
                    statement.setString(3, uuid.toString());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private void removeCampMember(UUID uuid) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM camp_members WHERE uuid = ?")) {
                    statement.setString(1, uuid.toString());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("UPDATE residents SET camp_id = NULL, updated_at = ? WHERE uuid = ?")) {
                    statement.setLong(1, System.currentTimeMillis());
                    statement.setString(2, uuid.toString());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private void setRole(UUID uuid, String role) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("UPDATE camp_members SET role = ? WHERE uuid = ?")) {
            statement.setString(1, role);
            statement.setString(2, uuid.toString());
            statement.executeUpdate();
        }
    }

    private void connectionalUpdateLeader(long campId, UUID oldLeader, UUID newLeader) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("UPDATE camps SET owner_uuid = ? WHERE id = ?")) {
                    statement.setString(1, newLeader.toString());
                    statement.setLong(2, campId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("UPDATE camp_members SET role = CASE WHEN uuid = ? THEN 'LEADER' WHEN uuid = ? THEN 'MEMBER' ELSE role END WHERE camp_id = ?")) {
                    statement.setString(1, newLeader.toString());
                    statement.setString(2, oldLeader.toString());
                    statement.setLong(3, campId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private Optional<CampInfo> campInfo(long campId) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM camps WHERE id = ?")) {
            statement.setLong(1, campId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapCampInfo(rs)) : Optional.empty();
            }
        }
    }

    private List<CampInfo> camps() throws SQLException {
        List<CampInfo> camps = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM camps")) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    camps.add(mapCampInfo(rs));
                }
            }
        }
        return camps;
    }

    private CampInfo mapCampInfo(ResultSet rs) throws SQLException {
        return new CampInfo(
                rs.getLong("id"),
                rs.getString("name"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                rs.getInt("level"),
                rs.getInt("experience"),
                rs.getInt("leadership_points"),
                rs.getInt("farm_upgrade") != 0,
                rs.getInt("breaker_upgrade") != 0,
                rs.getLong("created_at"),
                location(rs, "food_chest"),
                location(rs, "breaker_input"),
                location(rs, "breaker_output"),
                location(rs, "control_block"),
                rs.getInt("control_hp")
        );
    }

    private Location location(ResultSet rs, String prefix) throws SQLException {
        String world = rs.getString(prefix + "_world");
        if (world == null || world.isBlank()) {
            return null;
        }
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) {
            return null;
        }
        return new Location(bukkitWorld, rs.getInt(prefix + "_x"), rs.getInt(prefix + "_y"), rs.getInt(prefix + "_z"));
    }

    private List<CampMember> members(long campId) throws SQLException {
        List<CampMember> members = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM camp_members WHERE camp_id = ? ORDER BY CASE role WHEN 'LEADER' THEN 0 WHEN 'ELDER' THEN 1 ELSE 2 END, name")) {
            statement.setLong(1, campId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    members.add(mapMember(rs));
                }
            }
        }
        return members;
    }

    private List<StructureChunk> campChunks(long campId) throws SQLException {
        List<StructureChunk> chunks = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT sc.world, sc.chunk_x, sc.chunk_z
                FROM structure_chunks sc
                JOIN structure_builds sb ON sb.id = sc.build_id
                JOIN camps c ON c.owner_uuid = sb.builder_uuid OR (c.world = sb.world AND c.x = sb.x AND c.y = sb.y AND c.z = sb.z)
                WHERE c.id = ? AND sb.structure_id = ? AND sb.status <> 'DEMOLISHED'
                """)) {
            statement.setLong(1, campId);
            statement.setString(2, settings.campStructureId());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    chunks.add(new StructureChunk(rs.getString("world"), rs.getInt("chunk_x"), rs.getInt("chunk_z")));
                }
            }
        }
        return chunks;
    }

    private void requireSameCamp(CampMember actor, CampMember target) {
        if (actor.campId() != target.campId()) {
            throw new IllegalArgumentException(message("camp.error.not-same-camp", "Игрок не из вашего лагеря."));
        }
    }

    private UUID resolvePlayer(String name) {
        return Bukkit.getOfflinePlayer(name).getUniqueId();
    }

    private String playerName(UUID uuid) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        return player.getName() == null ? uuid.toString().substring(0, 8) : player.getName();
    }

    private String arg(String[] args, int index, String error) {
        if (args.length <= index || args[index].isBlank()) {
            throw new IllegalArgumentException(error);
        }
        return args[index];
    }

    private void buyUpgrade(Player player, long campId, String upgrade) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                economy.withdraw(player, 2500, "camp upgrade " + upgrade);
                String column = upgrade.equals("farm") ? "farm_upgrade" : "breaker_upgrade";
                try (PreparedStatement check = connection.prepareStatement("SELECT " + column + " FROM camps WHERE id = ?")) {
                    check.setLong(1, campId);
                    try (ResultSet rs = check.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("Camp not found");
                        }
                        if (rs.getInt(column) != 0) {
                            throw new IllegalArgumentException(message("camp.error.upgrade-owned", "Улучшение уже куплено."));
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("UPDATE camps SET " + column + " = 1 WHERE id = ?")) {
                    statement.setLong(1, campId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                try {
                    economy.deposit(player, 2500, "camp upgrade refund");
                } catch (SQLException ignored) {
                }
                throw exception;
            }
        }
    }

    private void removeCamp(long campId) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("UPDATE residents SET camp_id = NULL, updated_at = ? WHERE camp_id = ?")) {
                    statement.setLong(1, System.currentTimeMillis());
                    statement.setLong(2, campId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM camp_members WHERE camp_id = ?")) {
                    statement.setLong(1, campId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM camps WHERE id = ?")) {
                    statement.setLong(1, campId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private void addCampExperienceAndMoney(long campId, UUID leader, int xp, long money) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                CampInfo camp = campInfo(campId).orElseThrow();
                int newXp = camp.experience() + Math.max(0, xp);
                int newLevel = levelForExperience(newXp);
                try (PreparedStatement statement = connection.prepareStatement("UPDATE camps SET experience = ?, level = ? WHERE id = ?")) {
                    statement.setInt(1, newXp);
                    statement.setInt(2, newLevel);
                    statement.setLong(3, campId);
                    statement.executeUpdate();
                }
                addLeadershipPoint(connection, leader);
                try (PreparedStatement statement = connection.prepareStatement("UPDATE residents SET coins = coins + ?, updated_at = ? WHERE uuid = ?")) {
                    statement.setLong(1, money);
                    statement.setLong(2, System.currentTimeMillis());
                    statement.setString(3, leader.toString());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private void updateWorkstations(long campId, Location food, Location breakerInput, Location breakerOutput, Location controlBlock) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE camps SET
                food_chest_world = COALESCE(?, food_chest_world), food_chest_x = COALESCE(?, food_chest_x), food_chest_y = COALESCE(?, food_chest_y), food_chest_z = COALESCE(?, food_chest_z),
                breaker_input_world = COALESCE(?, breaker_input_world), breaker_input_x = COALESCE(?, breaker_input_x), breaker_input_y = COALESCE(?, breaker_input_y), breaker_input_z = COALESCE(?, breaker_input_z),
                breaker_output_world = COALESCE(?, breaker_output_world), breaker_output_x = COALESCE(?, breaker_output_x), breaker_output_y = COALESCE(?, breaker_output_y), breaker_output_z = COALESCE(?, breaker_output_z),
                control_block_world = COALESCE(?, control_block_world), control_block_x = COALESCE(?, control_block_x), control_block_y = COALESCE(?, control_block_y), control_block_z = COALESCE(?, control_block_z)
                WHERE id = ?
                """)) {
            bindLocation(statement, 1, food);
            bindLocation(statement, 5, breakerInput);
            bindLocation(statement, 9, breakerOutput);
            bindLocation(statement, 13, controlBlock);
            statement.setLong(17, campId);
            statement.executeUpdate();
        }
    }

    private void bindLocation(PreparedStatement statement, int index, Location location) throws SQLException {
        if (location == null || location.getWorld() == null) {
            statement.setNull(index, java.sql.Types.VARCHAR);
            statement.setNull(index + 1, java.sql.Types.INTEGER);
            statement.setNull(index + 2, java.sql.Types.INTEGER);
            statement.setNull(index + 3, java.sql.Types.INTEGER);
            return;
        }
        statement.setString(index, location.getWorld().getName());
        statement.setInt(index + 1, location.getBlockX());
        statement.setInt(index + 2, location.getBlockY());
        statement.setInt(index + 3, location.getBlockZ());
    }

    private boolean removeItems(Inventory inventory, Material material, int amount) {
        if (!inventory.containsAtLeast(new ItemStack(material), amount)) {
            return false;
        }
        int left = amount;
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType() != material) {
                continue;
            }
            int take = Math.min(left, item.getAmount());
            item.setAmount(item.getAmount() - take);
            left -= take;
            if (left <= 0) {
                return true;
            }
        }
        return true;
    }

    private boolean hasInventorySpace(Inventory inventory) {
        return inventory.firstEmpty() >= 0;
    }

    private Material randomBreakerDrop() {
        double value = Math.random();
        if (value < 0.80) return Material.GRAVEL;
        if (value < 0.90) return Material.IRON_INGOT;
        if (value < 0.97) return Material.GOLD_INGOT;
        if (value < 0.99) return Material.DIAMOND;
        return Material.EMERALD;
    }

    private CampLevel level(int level) {
        return switch (Math.max(1, Math.min(5, level))) {
            case 1 -> new CampLevel(50, 250);
            case 2 -> new CampLevel(100, 500);
            case 3 -> new CampLevel(200, 1250);
            case 4 -> new CampLevel(300, 2000);
            default -> new CampLevel(0, 4000);
        };
    }

    private int breadRequired(int level, int membersCount) {
        int base = switch (Math.max(1, Math.min(5, level))) {
            case 1 -> 2;
            case 2 -> 4;
            case 3 -> 8;
            case 4 -> 12;
            default -> 20;
        };
        return base + Math.max(0, membersCount / 2);
    }

    private int nextLevelXp(int level) {
        return switch (level) {
            case 1 -> 100;
            case 2 -> 300;
            case 3 -> 600;
            case 4 -> 1200;
            default -> 1200;
        };
    }

    private int levelForExperience(int experience) {
        if (experience >= 1200) return 5;
        if (experience >= 600) return 4;
        if (experience >= 300) return 3;
        if (experience >= 100) return 2;
        return 1;
    }


    private int memberCount(long campId) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) AS count FROM camp_members WHERE camp_id = ?")) {
            statement.setLong(1, campId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt("count") : 0;
            }
        }
    }

    private boolean isControlAttackReady(CampInfo camp) {
        return controlAttackTimeLeft(camp) <= 0L;
    }

    public boolean isCampMemberAt(UUID playerUuid, String world, int chunkX, int chunkZ) {
        try {
            Optional<Long> campId = campIdAtChunk(world, chunkX, chunkZ);
            if (campId.isEmpty()) {
                return false;
            }
            Optional<CampMember> member = findCampMember(playerUuid);
            return member.isPresent() && member.get().campId() == campId.get();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to check camp member access: " + exception.getMessage());
            return false;
        }
    }

    public boolean isCampProtectedBlock(Block block) {
        try {
            if (campIdAtChunk(block.getWorld().getName(), block.getChunk().getX(), block.getChunk().getZ()).isEmpty()) {
                return false;
            }
            try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM protected_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                statement.setString(1, block.getWorld().getName());
                statement.setInt(2, block.getX());
                statement.setInt(3, block.getY());
                statement.setInt(4, block.getZ());
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to check camp protected block: " + exception.getMessage());
            return false;
        }
    }

    public boolean handleControlBlockAttack(Player player, Block block) {
        try {
            Optional<CampInfo> campOptional = controlBlockCamp(block);
            if (campOptional.isEmpty()) {
                return false;
            }
            CampInfo camp = campOptional.get();
            if (!isControlAttackReady(camp)) {
                player.sendMessage(msg("camp.control.locked", "Контрольный блок лагеря будет доступен для атаки через {time}.", "{time}", formatDuration(controlAttackTimeLeft(camp))));
                return true;
            }
            int newHp = Math.max(0, camp.controlHp() - 1);
            try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("UPDATE camps SET control_hp = ? WHERE id = ?")) {
                statement.setInt(1, newHp);
                statement.setLong(2, camp.id());
                statement.executeUpdate();
            }
            CampInfo updated = campInfo(camp.id()).orElse(camp);
            updateControlHologram(updated);
            if (newHp <= 0) {
                destroyCamp(camp.id(), true);
                player.sendMessage(message("camp.control.destroyed", "Контрольный блок лагеря разрушен."));
            } else {
                player.sendMessage(msg("camp.control.hit", "Контрольный блок лагеря: {hp}/{max_hp} HP.", "{hp}", String.valueOf(newHp), "{max_hp}", String.valueOf(CONTROL_MAX_HP)));
            }
            return true;
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to process camp control block attack: " + exception.getMessage());
            return false;
        }
    }

    private Optional<CampInfo> controlBlockCamp(Block block) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM camps
                WHERE control_block_world = ? AND control_block_x = ? AND control_block_y = ? AND control_block_z = ?
                """)) {
            statement.setString(1, block.getWorld().getName());
            statement.setInt(2, block.getX());
            statement.setInt(3, block.getY());
            statement.setInt(4, block.getZ());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapCampInfo(rs)) : Optional.empty();
            }
        }
    }

    private Optional<Long> campIdAtChunk(String world, int chunkX, int chunkZ) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT c.id
                FROM structure_chunks sc
                JOIN structure_builds sb ON sb.id = sc.build_id
                JOIN camps c ON c.owner_uuid = sb.builder_uuid OR (c.world = sb.world AND c.x = sb.x AND c.y = sb.y AND c.z = sb.z)
                WHERE sc.world = ? AND sc.chunk_x = ? AND sc.chunk_z = ? AND sb.structure_id = ? AND sb.status <> 'DEMOLISHED'
                LIMIT 1
                """)) {
            statement.setString(1, world);
            statement.setInt(2, chunkX);
            statement.setInt(3, chunkZ);
            statement.setString(4, settings.campStructureId());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong("id")) : Optional.empty();
            }
        }
    }


    public Optional<CampPlaceholderInfo> placeholderInfo(UUID uuid) {
        try {
            Optional<CampMember> member = findCampMember(uuid);
            if (member.isEmpty()) {
                return Optional.empty();
            }
            Optional<CampInfo> camp = campInfo(member.get().campId());
            if (camp.isEmpty()) {
                return Optional.empty();
            }
            CampInfo info = camp.get();
            return Optional.of(new CampPlaceholderInfo(info.name(), playerName(info.leaderUuid()), info.level(), info.experience(), nextLevelXp(info.level())));
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    public Optional<String> campNameAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return Optional.empty();
        }
        try {
            Optional<Long> campId = campIdAtChunk(location.getWorld().getName(), location.getChunk().getX(), location.getChunk().getZ());
            if (campId.isEmpty()) {
                return Optional.empty();
            }
            return campInfo(campId.get()).map(CampInfo::name);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to check camp greeting: " + exception.getMessage());
            return Optional.empty();
        }
    }

    private void addLeadershipPoint(Connection connection, UUID leader) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO camp_leadership_points(uuid, points)
                VALUES(?, 1)
                ON CONFLICT(uuid) DO UPDATE SET points = MIN(24, points + 1)
                """)) {
            statement.setString(1, leader.toString());
            statement.executeUpdate();
        }
    }

    private int leadershipPoints(UUID leader) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("SELECT points FROM camp_leadership_points WHERE uuid = ?")) {
            statement.setString(1, leader.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt("points") : 0;
            }
        }
    }

    private long controlAttackTimeLeft(CampInfo camp) {
        return Math.max(0L, CONTROL_ATTACK_DELAY_MILLIS - (System.currentTimeMillis() - camp.createdAt()));
    }

    private String raidStatus(CampInfo camp) {
        long left = controlAttackTimeLeft(camp);
        return left <= 0L ? message("camp.raid.available", "доступен") : msg("camp.raid.left", "через {time}", "{time}", formatDuration(left));
    }

    private String formatDuration(long millis) {
        long seconds = Math.max(0L, (long) Math.ceil(millis / 1000.0));
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        if (hours > 0L) {
            return hours + "ч " + minutes + "м";
        }
        if (minutes > 0L) {
            return minutes + "м " + secs + "с";
        }
        return secs + "с";
    }

    private void updateControlHologram(CampInfo camp) {
        Location control = camp.controlBlockLocation();
        if (control == null || control.getWorld() == null) {
            return;
        }
        Location holoLocation = control.clone().add(0.5, 1.35, 0.5);
        String line = msg("camp.control.hologram", "§cКонтрольный блок: {hp}/{max_hp} HP", "{hp}", String.valueOf(camp.controlHp()), "{max_hp}", String.valueOf(CONTROL_MAX_HP));
        if (updateDecentHologram(camp.id(), holoLocation, line)) {
            removeNearbyArmorStandHolograms(holoLocation);
            return;
        }
        updateArmorStandHologram(holoLocation, component(line));
    }

    private boolean updateDecentHologram(long campId, Location location, String line) {
        if (!Bukkit.getPluginManager().isPluginEnabled("DecentHolograms")) {
            return false;
        }
        try {
            Class<?> dhApi = Class.forName("eu.decentsoftware.holograms.api.DHAPI");
            String name = decentHologramName(campId);
            Object hologram = invokeStatic(dhApi, "getHologram", name);
            if (hologram == null) {
                invokeStatic(dhApi, "createHologram", name, location, List.of(line));
                return true;
            }
            invokeStatic(dhApi, "moveHologram", hologram, location);
            invokeStatic(dhApi, "setHologramLines", hologram, List.of(line));
            return true;
        } catch (Throwable exception) {
            if (settings.debug()) {
                plugin.getLogger().warning("DecentHolograms update failed, using armorstand fallback: " + exception.getMessage());
            }
            return false;
        }
    }

    private Object invokeStatic(Class<?> type, String methodName, Object... args) throws ReflectiveOperationException {
        for (Method method : type.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || !method.getName().equals(methodName) || method.getParameterCount() != args.length) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                if (args[i] != null && !wrap(parameterTypes[i]).isAssignableFrom(args[i].getClass())) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return method.invoke(null, args);
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + methodName);
    }

    private Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == boolean.class) return Boolean.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private String decentHologramName(long campId) {
        return "civcraft_camp_control_" + campId;
    }

    private void updateArmorStandHologram(Location holoLocation, Component name) {
        ArmorStand existing = null;
        for (Entity entity : holoLocation.getWorld().getNearbyEntities(holoLocation, 1.0, 1.0, 1.0)) {
            if (entity instanceof ArmorStand stand && stand.customName() != null) {
                String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(stand.customName());
                if (plain.startsWith("Контрольный блок:")) {
                    if (existing == null) {
                        existing = stand;
                    } else {
                        stand.remove();
                    }
                }
            }
        }
        if (existing != null) {
            existing.teleport(holoLocation);
            existing.customName(name);
            existing.setCustomNameVisible(true);
            return;
        }
        holoLocation.getWorld().spawn(holoLocation, ArmorStand.class, armorStand -> {
            armorStand.setInvisible(true);
            armorStand.setMarker(true);
            armorStand.setGravity(false);
            armorStand.setInvulnerable(true);
            armorStand.customName(name);
            armorStand.setCustomNameVisible(true);
        });
    }

    private void removeNearbyArmorStandHolograms(Location holoLocation) {
        for (Entity entity : holoLocation.getWorld().getNearbyEntities(holoLocation, 2.0, 2.0, 2.0)) {
            if (entity instanceof ArmorStand stand && stand.customName() != null) {
                String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(stand.customName());
                if (plain.startsWith("Контрольный блок:")) {
                    stand.remove();
                }
            }
        }
    }

    private void destroyCamp(long campId, boolean showTitle) throws SQLException {
        List<CampMember> campMembers = members(campId);
        airOutHalfCampBlocks(campId);
        releaseCampStructureProtection(campId);
        removeCampHolograms(campId);
        removeCamp(campId);
        if (showTitle) {
            Title title = Title.title(
                    component(message("camp.destroyed.title", "§cЛагерь разрушен")),
                    component(message("camp.destroyed.subtitle", "")),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(200))
            );
            for (CampMember member : campMembers) {
                Player player = Bukkit.getPlayer(member.uuid());
                if (player != null) {
                    player.showTitle(title);
                }
            }
        }
    }

    private void airOutHalfCampBlocks(long campId) throws SQLException {
        List<BlockLocation> blocks = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT pb.world, pb.x, pb.y, pb.z
                FROM protected_blocks pb
                JOIN structure_builds sb ON sb.id = pb.build_id
                JOIN camps c ON c.owner_uuid = sb.builder_uuid OR (c.world = sb.world AND c.x = sb.x AND c.y = sb.y AND c.z = sb.z)
                WHERE c.id = ? AND sb.structure_id = ? AND sb.status <> 'DEMOLISHED'
                """)) {
            statement.setLong(1, campId);
            statement.setString(2, settings.campStructureId());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    blocks.add(new BlockLocation(rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z")));
                }
            }
        }
        for (BlockLocation loc : blocks) {
            if (!random.nextBoolean()) {
                continue;
            }
            World world = Bukkit.getWorld(loc.world());
            if (world != null) {
                world.getBlockAt(loc.x(), loc.y(), loc.z()).setType(Material.AIR, false);
            }
        }
    }

    private void releaseCampStructureProtection(long campId) throws SQLException {
        List<Long> buildIds = campBuildIds(campId);
        if (buildIds.isEmpty()) {
            return;
        }
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                for (Long buildId : buildIds) {
                    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM protected_blocks WHERE build_id = ?")) {
                        statement.setLong(1, buildId);
                        statement.executeUpdate();
                    }
                    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM structure_chunks WHERE build_id = ?")) {
                        statement.setLong(1, buildId);
                        statement.executeUpdate();
                    }
                    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM structure_block_backups WHERE build_id = ?")) {
                        statement.setLong(1, buildId);
                        statement.executeUpdate();
                    }
                    try (PreparedStatement statement = connection.prepareStatement("UPDATE structure_builds SET status = 'DEMOLISHED', completed_at = ? WHERE id = ?")) {
                        statement.setLong(1, System.currentTimeMillis());
                        statement.setLong(2, buildId);
                        statement.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private List<Long> campBuildIds(long campId) throws SQLException {
        List<Long> buildIds = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT DISTINCT sb.id
                FROM structure_builds sb
                JOIN camps c ON c.owner_uuid = sb.builder_uuid OR (c.world = sb.world AND c.x = sb.x AND c.y = sb.y AND c.z = sb.z)
                WHERE c.id = ? AND sb.structure_id = ? AND sb.status <> 'DEMOLISHED'
                """)) {
            statement.setLong(1, campId);
            statement.setString(2, settings.campStructureId());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    buildIds.add(rs.getLong("id"));
                }
            }
        }
        return buildIds;
    }

    private void removeCampHolograms(long campId) throws SQLException {
        removeDecentHologram(campId);
        Optional<CampInfo> camp = campInfo(campId);
        if (camp.isEmpty() || camp.get().controlBlockLocation() == null || camp.get().controlBlockLocation().getWorld() == null) {
            return;
        }
        Location holoLocation = camp.get().controlBlockLocation().clone().add(0.5, 1.35, 0.5);
        removeNearbyArmorStandHolograms(holoLocation);
    }

    private void removeDecentHologram(long campId) {
        if (!Bukkit.getPluginManager().isPluginEnabled("DecentHolograms")) {
            return;
        }
        try {
            Class<?> dhApi = Class.forName("eu.decentsoftware.holograms.api.DHAPI");
            invokeStatic(dhApi, "removeHologram", decentHologramName(campId));
        } catch (Throwable exception) {
            if (settings.debug()) {
                plugin.getLogger().warning("Unable to remove DecentHolograms camp hologram: " + exception.getMessage());
            }
        }
    }

    private int countBreakerInputs(Inventory inventory) {
        int count = 0;
        for (Material material : breakerInputMaterials()) {
            count += countItems(inventory, material);
        }
        return count;
    }

    private boolean removeOneBreakerInput(Inventory inventory) {
        for (Material material : breakerInputMaterials()) {
            if (removeItems(inventory, material, 1)) {
                return true;
            }
        }
        return false;
    }

    private List<Material> breakerInputMaterials() {
        return List.of(Material.COBBLESTONE, Material.ANDESITE, Material.DIORITE, Material.GRANITE);
    }

    private boolean canFitItems(Inventory inventory, List<Material> materials) {
        Map<Material, Integer> required = new HashMap<>();
        for (Material material : materials) {
            required.merge(material, 1, Integer::sum);
        }

        int emptySlots = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType() == Material.AIR) {
                emptySlots++;
                continue;
            }
            Integer amount = required.get(item.getType());
            if (amount == null || amount <= 0) {
                continue;
            }
            int free = Math.max(0, item.getMaxStackSize() - item.getAmount());
            if (free > 0) {
                required.put(item.getType(), Math.max(0, amount - free));
            }
        }

        for (Map.Entry<Material, Integer> entry : required.entrySet()) {
            int amount = entry.getValue();
            if (amount <= 0) {
                continue;
            }
            int maxStack = Math.max(1, entry.getKey().getMaxStackSize());
            int neededSlots = (int) Math.ceil(amount / (double) maxStack);
            emptySlots -= neededSlots;
            if (emptySlots < 0) {
                return false;
            }
        }
        return true;
    }

    private int countItems(Inventory inventory, Material material) {
        int count = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private String blockKey(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private String yesNo(boolean value) {
        return value ? message("camp.common.true", message("camp.common.yes", "&aКуплено")) : message("camp.common.false", message("camp.common.no", "&cНе куплено"));
    }

    public void showCampGreeting(Player player, String campName) {
        Title title = Title.title(
                component(msg("camp.greeting.title", "§6Лагерь §e{name}", "{name}", campName)),
                component(message("camp.greeting.subtitle", "")),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(300))
        );
        player.showTitle(title);
    }

    public String message(String path, String fallback) {
        String value = messages == null ? null : messages.getString(path);
        return colorize(value == null ? fallback : value);
    }

    private String msg(String path, String fallback, String... replacements) {
        String result = message(path, fallback);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            result = result.replace(replacements[i], replacements[i + 1]);
        }
        return result;
    }

    private Component component(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(colorize(text == null ? "" : text));
    }

    private String colorize(String text) {
        return text == null ? "" : text.replace('&', '§');
    }

    private void reloadMessages() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (InputStream stream = plugin.getResource("messages.yml")) {
            if (stream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
                loaded.setDefaults(defaults);
                loaded.options().copyDefaults(true);
                loaded.save(file);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to merge messages.yml defaults: " + exception.getMessage());
        }
        this.messages = loaded;
    }

    private record PendingInvite(long campId, String campName, UUID inviter, long expiresAt) {}
    private record CampMember(long campId, UUID uuid, String name, String role) {}
    private record StructureChunk(String world, int chunkX, int chunkZ) {}
    private record BlockLocation(String world, int x, int y, int z) {}
    public record CampPlaceholderInfo(String name, String leaderName, int level, int experience, int nextLevelExperience) {}
    private record CampLevel(int experience, long money) {}
    private record Workstations(Location food, Location breakerInput, Location breakerOutput, Location controlBlock) {}

    private record CampInfo(long id, String name, UUID leaderUuid, String world, int x, int y, int z, int level, int experience, int leadershipPoints, boolean farmUpgrade, boolean breakerUpgrade, long createdAt, Location foodChestLocation, Location breakerInputLocation, Location breakerOutputLocation, Location controlBlockLocation, int controlHp) {
        boolean hasAllWorkstations() {
            return foodChestLocation != null && breakerInputLocation != null && breakerOutputLocation != null && controlBlockLocation != null;
        }
    }
}
