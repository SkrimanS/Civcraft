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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

public final class CampService {
    private static final String ROLE_LEADER = "LEADER";
    private static final String ROLE_ELDER = "ELDER";
    private static final String ROLE_MEMBER = "MEMBER";
    private static final long TP_COOLDOWN_MILLIS = 30L * 60L * 1000L;

    private static final String CONTROL_HOLOGRAM_PREFIX = "§cКонтрольный блок: ";

    private final JavaPlugin plugin;
    private final CivCraftGameService game;
    private final CivCraftEconomyService economy;
    private ModernCivCraftSettings settings;
    private final Map<UUID, PendingInvite> invites = new ConcurrentHashMap<>();
    private final Map<UUID, Long> teleportCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> disbandConfirmations = new ConcurrentHashMap<>();

    private volatile YamlConfiguration messages;

    private final CampLonghouseService longhouse;
    private final CampTemplateService templates;
    private final CampFireService fire;
    private final CampRuinService ruins;
    private final long raidLengthMillis;

    public CampService(JavaPlugin plugin, CivCraftGameService game, CivCraftEconomyService economy, ModernCivCraftSettings settings) {
        this.plugin = plugin;
        this.game = game;
        this.economy = economy;
        this.settings = settings;
        reloadMessages();
        initializeStorage();
        int raidHours=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/camp.yml")).getInt("camp.raid_length",2);
        if(raidHours<1 || raidHours>24)throw new IllegalArgumentException("Неверная длительность ежедневного рейда лагеря");
        raidLengthMillis=raidHours*3600000L;
        try {
            int base=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/war.yml")).getInt("war.control_block_hitpoints_camp",100);
            try(Connection connection=connect()){connection.setAutoCommit(false);try{CampRaidStore.install(connection,base);connection.commit();}catch(SQLException | RuntimeException failure){connection.rollback();throw failure;}}
            templates = new CampTemplateService((com.avrgaming.civcraft.modern.CivCraftModernPlugin) plugin,settings.campStructureId());
            longhouse = new CampLonghouseService((com.avrgaming.civcraft.modern.CivCraftModernPlugin) plugin);
            fire = new CampFireService((com.avrgaming.civcraft.modern.CivCraftModernPlugin) plugin,templates);
            ruins = new CampRuinService((com.avrgaming.civcraft.modern.CivCraftModernPlugin) plugin,settings.campStructureId(),templates,this::removeCampHolograms);
        } catch (SQLException | IOException exception) {
            throw new IllegalStateException("Unable to initialize native camp longhouse", exception);
        }
    }

    public void updateSettings(ModernCivCraftSettings settings) {
        this.settings = settings;
        reloadMessages();
    }

    public void startTasks() {
        plugin.getServer().getScheduler().runTask(plugin, this::setupMissingWorkstations);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::setupMissingWorkstations, 20L, 20L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickBreaker, 20L * 20L, 20L * 20L);

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
            case "upgrade" -> upgrade(player, arg(args, 2, message("camp.error.specify-upgrade", "Укажите улучшение: farm, breaker или longhouse.")));
            default -> help(player);
        }
        return true;
    }

    public List<String> completions(int argIndex) {
        if (argIndex == 1) {
            return List.of("info", "invite", "kick", "promote", "lower", "setleader", "disband", "leave", "tp", "teleport", "upgrade", "accept", "decline");
        }
        if (argIndex == 2) {
            return List.of("farm", "breaker", "longhouse", "garden", "sifter");
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
        player.sendMessage(message("camp.help.upgrade", "§e/c upgrade farm|breaker|longhouse §7- купить улучшение лагеря"));
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
        player.sendMessage(msg("camp.info.level", "§7Уровень большого дома: §e{level}§7, питание: §e{experience}/{next}", "{level}", String.valueOf(camp.level()), "{experience}", String.valueOf(camp.experience()), "{next}", String.valueOf(nextLevelXp(camp.level()))));
        player.sendMessage(msg("camp.info.bread", "§7Хлеб в час: §e{bread}§7, жителей: §e{members}", "{bread}", String.valueOf(breadRequired(camp.level(), membersCount)), "{members}", String.valueOf(membersCount)));
        player.sendMessage(msg("camp.info.control", "§7Контрольный блок: §e{hp}/{max_hp} HP§7, рейд: §e{raid}", "{hp}", String.valueOf(camp.controlHp()), "{max_hp}", String.valueOf(camp.controlMaximum()), "{raid}", raidStatus(camp)));
        player.sendMessage("§7За каждый успешный час питания жетон лидерства появляется в сундуке большого дома.");
        player.sendMessage("§7Большой дом: " + (longhouseEnabled(camp.id()) ? "§aкуплен" : "§cне куплен"));
        player.sendMessage("§7Костёр: §e"+Math.max(0,fire.points(camp.id()))+"/"+fire.maximum()+" ч.§7, угля за час: §e"+fire.coalPerPoint());
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
        ruins.requestRestore(self.campId());
        player.sendMessage(message("camp.disband.started", "Роспуск лагеря запущен. Местность восстанавливается."));
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
        if (clean.equals("garden")) clean = "farm";
        if (clean.equals("sifter")) clean = "breaker";
        if (!clean.equals("farm") && !clean.equals("breaker") && !clean.equals("longhouse")) {
            throw new IllegalArgumentException(message("camp.error.invalid-upgrade", "Улучшение должно быть farm, breaker или longhouse."));
        }
        buyUpgrade(player, self.campId(), clean);
        templates.refresh();
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
        fire.runHour();
        longhouse.runHour();
    }

    private void tickBreaker() { longhouse.runSifters(); }

    public boolean isGardenGrowthAllowed(Block block) { return templates.allowsGrowth(block); }
    public boolean isGardenSoil(Block block) { return templates.soil(block); }
    public boolean canEditGarden(Player player,Block block) { return templates.editable(player,block); }

    private void setupMissingWorkstations() {
        try {
            for (CampInfo camp : camps()) {
                if(ruins.ruining(camp.id()))continue;
                boolean needsWorkstations = !camp.hasAllWorkstations();
                boolean needsNpcMarker = !hasCampNpcMarker(camp.id()) && !templates.indexed(camp.id());
                if (!templates.indexed(camp.id()) && (needsWorkstations || needsNpcMarker)) {
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

    private void initializeStorage() {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS camp_members(camp_id INTEGER NOT NULL, uuid TEXT PRIMARY KEY, name TEXT NOT NULL, role TEXT NOT NULL, joined_at INTEGER NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS camp_leadership_points(uuid TEXT PRIMARY KEY, points INTEGER NOT NULL DEFAULT 0)");
            addColumnIfMissing(connection, "camps", "level", "INTEGER NOT NULL DEFAULT 1");
            addColumnIfMissing(connection, "camps", "experience", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(connection, "camps", "leadership_points", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(connection, "camps", "farm_upgrade", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(connection, "camps", "breaker_upgrade", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(connection, "camps", "longhouse_upgrade", "INTEGER NOT NULL DEFAULT 0");
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
            CampLonghouseStore.requireUnlocked(connection,campId);
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
                rs.getInt("control_hp"),
                rs.getInt("control_max_hp")
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

    private boolean longhouseEnabled(long campId) throws SQLException {
        try (Connection connection=connect();var statement=connection.prepareStatement("SELECT longhouse_upgrade FROM camps WHERE id=?")) {
            statement.setLong(1,campId);try(var rs=statement.executeQuery()){return rs.next() && rs.getInt(1)!=0;}
        }
    }

    private int breadRequired(int level,int ignoredMembers) { return longhouse.bread(level); }
    private int nextLevelXp(int level) { return longhouse.maximum(level); }

    private double upgradeCost(String upgrade) {
        String action=switch(upgrade){case "farm"->"enable_garden";case "breaker"->"enable_sifter";case "longhouse"->"enable_longhouse";default->throw new IllegalArgumentException("Неизвестное улучшение лагеря.");};
        var yaml=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/camp.yml"));
        for(var row:yaml.getMapList("upgrades"))if(action.equals(row.get("action")) && row.get("cost") instanceof Number value) {
            double cost=value.doubleValue();if(Double.isFinite(cost) && cost>=0)return cost;
        }
        throw new IllegalStateException("Missing camp upgrade price: "+upgrade);
    }

    private void buyUpgrade(Player player, long campId, String upgrade) throws SQLException {
        double cost=upgradeCost(upgrade);
        boolean external= economy.usesExternalProvider(),paid=false;
        String column=switch(upgrade){case "farm"->"farm_upgrade";case "breaker"->"breaker_upgrade";case "longhouse"->"longhouse_upgrade";default->throw new IllegalArgumentException("Неизвестное улучшение лагеря.");};
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                CampLonghouseStore.requireUnlocked(connection,campId);
                try(var check=connection.prepareStatement("SELECT "+column+" FROM camps WHERE id=? AND owner_uuid=?")) {
                    check.setLong(1,campId);check.setString(2,player.getUniqueId().toString());
                    try(var rs=check.executeQuery()) {
                        if(!rs.next())throw new SQLException("Camp or leader changed");
                        if(rs.getInt(1)!=0)throw new IllegalArgumentException(message("camp.error.upgrade-owned","Улучшение уже куплено."));
                    }
                }
                if(external){economy.withdraw(player,cost,"camp upgrade "+upgrade);paid=true;}
                else try(var charge=connection.prepareStatement("UPDATE residents SET coins=coins-?,updated_at=? WHERE uuid=? AND coins>=?")) {
                    charge.setDouble(1,cost);charge.setLong(2,System.currentTimeMillis());charge.setString(3,player.getUniqueId().toString());charge.setDouble(4,cost);
                    if(charge.executeUpdate()!=1)throw new IllegalArgumentException(message("camp.error.upgrade-money","Недостаточно монет для улучшения лагеря."));
                }
                try(var update=connection.prepareStatement("UPDATE camps SET "+column+"=1 WHERE id=?")){update.setLong(1,campId);if(update.executeUpdate()!=1)throw new SQLException("Camp disappeared");}
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                if(external && paid)try{economy.deposit(player,cost,"camp upgrade refund");}catch(SQLException refundFailure){exception.addSuppressed(refundFailure);}
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

    private int memberCount(long campId) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) AS count FROM camp_members WHERE camp_id = ?")) {
            statement.setLong(1, campId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt("count") : 0;
            }
        }
    }

    private boolean isControlAttackReady(CampInfo camp)throws SQLException {
        return raidWindow(camp).open();
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
            if(plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin civ && civ.newbieProtection()!=null && civ.newbieProtection().protectedPlayer(player.getUniqueId())) {
                player.sendMessage("§cПод PvP-защитой нельзя атаковать лагерь. Отключить защиту: /res pvptimer.");
                return true;
            }
            if (!isControlAttackReady(camp)) {
                player.sendMessage(msg("camp.control.locked", "Контрольный блок лагеря будет доступен для атаки через {time}.", "{time}", formatDuration(controlAttackTimeLeft(camp))));
                return true;
            }
            int newHp;
            try (Connection connection = connect()) {
                connection.setAutoCommit(false);
                try{newHp=CampRaidStore.hit(connection,camp.id(),raidLengthMillis,System.currentTimeMillis()).health();connection.commit();}
                catch(SQLException | RuntimeException failure){connection.rollback();throw failure;}
            }
            CampInfo updated = campInfo(camp.id()).orElse(camp);
            updateControlHologram(updated);
            if (newHp <= 0) {
                block.getWorld().playSound(block.getLocation(),org.bukkit.Sound.BLOCK_ANVIL_BREAK,1,.5f);
                block.getWorld().playSound(block.getLocation(),org.bukkit.Sound.ENTITY_GENERIC_EXPLODE,1,1);
                destroyCamp(camp.id(), true);
                player.sendMessage(message("camp.control.destroyed", "Контрольный блок лагеря разрушен."));
            } else {
                block.getWorld().playSound(block.getLocation(),org.bukkit.Sound.BLOCK_ANVIL_USE,.2f,1);
                block.getWorld().spawnParticle(org.bukkit.Particle.FLAME,block.getLocation().add(.5,.5,.5),4,.2,.2,.2,0);
                for(var member:members(camp.id())){var online=Bukkit.getPlayer(member.uuid());if(online!=null)online.sendMessage("§eКонтрольный блок лагеря атакован!");}
                player.sendMessage(msg("camp.control.hit", "Контрольный блок лагеря: {hp}/{max_hp} HP.", "{hp}", String.valueOf(newHp), "{max_hp}", String.valueOf(camp.controlMaximum())));
            }
            return true;
        } catch (IllegalArgumentException pending) {
            player.sendMessage(pending.getMessage());
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

    private CampRaidRules.Window raidWindow(CampInfo camp)throws SQLException {
        try(Connection connection=connect()){return CampRaidStore.window(connection,camp.id(),raidLengthMillis,System.currentTimeMillis());}
    }
    private long controlAttackTimeLeft(CampInfo camp)throws SQLException {
        var window=raidWindow(camp);return window.open()?0:Math.max(1,window.start()-System.currentTimeMillis());
    }
    private String raidStatus(CampInfo camp)throws SQLException {
        var window=raidWindow(camp);long now=System.currentTimeMillis();
        return window.open()?message("camp.raid.available","доступен")+", ещё "+formatDuration(Math.max(0,window.end()-now)):msg("camp.raid.left", "через {time}", "{time}", formatDuration(Math.max(1,window.start()-now)));
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
        String line = msg("camp.control.hologram", "§cКонтрольный блок: {hp}/{max_hp} HP", "{hp}", String.valueOf(camp.controlHp()), "{max_hp}", String.valueOf(camp.controlMaximum()));
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
        ruins.request(campId,showTitle);
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
                // Replace only shipped obsolete wording; preserve administrator translations.
                Map<String,String> obsolete = Map.of(
                    "camp.help.upgrade", "§e/c upgrade farm / breaker §7- купить улучшение. Стоимость 2500 монет",
                    "camp.info.level", "§7Уровень: §e{level}§7, опыт: §e{experience}/{next}",
                    "camp.error.specify-upgrade", "&cУкажите улучшение: farm / breaker.",
                    "camp.error.invalid-upgrade", "&cИспользуйте /camp upgrade farm / breaker."
                );
                obsolete.forEach((key,old)->{if(old.equals(loaded.getString(key)))loaded.set(key,defaults.getString(key));});
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

    private record Workstations(Location food, Location breakerInput, Location breakerOutput, Location controlBlock) {}

    private record CampInfo(long id, String name, UUID leaderUuid, String world, int x, int y, int z, int level, int experience, int leadershipPoints, boolean farmUpgrade, boolean breakerUpgrade, long createdAt, Location foodChestLocation, Location breakerInputLocation, Location breakerOutputLocation, Location controlBlockLocation, int controlHp,int controlMaximum) {
        boolean hasAllWorkstations() {
            return foodChestLocation != null && breakerInputLocation != null && breakerOutputLocation != null && controlBlockLocation != null;
        }
    }
}
