package com.avrgaming.civcraft.modern.api;

import com.avrgaming.civcraft.modern.config.ModernCivCraftSettings;
import com.avrgaming.civcraft.modern.economy.CivCraftEconomyService;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class CivCraftCampApi {
    private static final int FALLBACK_SCAN_RADIUS_BLOCKS = 48;

    private final JavaPlugin plugin;
    private final CivCraftEconomyService economy;
    private ModernCivCraftSettings settings;

    public CivCraftCampApi(JavaPlugin plugin, ModernCivCraftSettings settings) {
        this(plugin, settings, null);
    }

    public CivCraftCampApi(JavaPlugin plugin, ModernCivCraftSettings settings, CivCraftEconomyService economy) {
        this.plugin = plugin;
        this.settings = settings;
        this.economy = economy;
    }

    public void updateSettings(ModernCivCraftSettings settings) {
        this.settings = settings;
    }

    public void initializeStorage() {
        try (Connection connection = connect()) {
            ensureColumn(connection, "camps", "npc_marker_world", "TEXT");
            ensureColumn(connection, "camps", "npc_marker_x", "INTEGER");
            ensureColumn(connection, "camps", "npc_marker_y", "INTEGER");
            ensureColumn(connection, "camps", "npc_marker_z", "INTEGER");
            ensureColumn(connection, "camps", "control_max_hp", "INTEGER NOT NULL DEFAULT 50");
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to initialize CivCraft camp API storage: " + exception.getMessage());
        }
    }

    public void startNpcMarkerScanner() {
        plugin.getServer().getScheduler().runTaskLater(plugin, this::scanMissingCampNpcMarkersSafe, 40L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::scanMissingCampNpcMarkersSafe, 20L * 15L, 20L * 60L);
    }

    public List<CampNpcMarker> getCampNpcMarkers() {
        // Cheap safety pass. If the server has just finished building a camp, this fills marker columns
        // before Typewriter asks for the marker list.
        scanMissingCampNpcMarkersSafe();

        List<CampNpcMarker> markers = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT id, name, npc_marker_world, npc_marker_x, npc_marker_y, npc_marker_z
                FROM camps
                WHERE npc_marker_world IS NOT NULL
                  AND npc_marker_world <> ''
                  AND npc_marker_x IS NOT NULL
                  AND npc_marker_y IS NOT NULL
                  AND npc_marker_z IS NOT NULL
                """)) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    markers.add(new CampNpcMarker(
                            rs.getLong("id"),
                            rs.getString("name"),
                            rs.getString("npc_marker_world"),
                            rs.getInt("npc_marker_x"),
                            rs.getInt("npc_marker_y"),
                            rs.getInt("npc_marker_z")
                    ));
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to load camp NPC markers: " + exception.getMessage());
        }
        return markers;
    }

    public boolean isCampActive(long campId) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM camps WHERE id = ? LIMIT 1")) {
            statement.setLong(1, campId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to check camp active state: " + exception.getMessage());
            return false;
        }
    }

    public boolean isCampMember(UUID playerUuid, long campId) {
        CampRole role = getCampRole(playerUuid, campId);
        return role == CampRole.LEADER || role == CampRole.ELDER || role == CampRole.MEMBER;
    }

    public CampRole getCampRole(UUID playerUuid, long campId) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("SELECT role FROM camp_members WHERE uuid = ? AND camp_id = ? LIMIT 1")) {
            statement.setString(1, playerUuid.toString());
            statement.setLong(2, campId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? CampRole.fromStorage(rs.getString("role")) : CampRole.NONE;
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to get camp role: " + exception.getMessage());
            return CampRole.NONE;
        }
    }

    public Optional<CampMemberInfo> getPlayerCamp(UUID playerUuid) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT c.id, c.name, cm.role
                FROM camp_members cm
                JOIN camps c ON c.id = cm.camp_id
                WHERE cm.uuid = ?
                LIMIT 1
                """)) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new CampMemberInfo(
                        rs.getLong("id"),
                        rs.getString("name"),
                        CampRole.fromStorage(rs.getString("role"))
                ));
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to get player camp: " + exception.getMessage());
            return Optional.empty();
        }
    }

    public boolean hasCampNpcMarker(long campId) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM camps
                WHERE id = ?
                  AND npc_marker_world IS NOT NULL
                  AND npc_marker_world <> ''
                  AND npc_marker_x IS NOT NULL
                  AND npc_marker_y IS NOT NULL
                  AND npc_marker_z IS NOT NULL
                LIMIT 1
                """)) {
            statement.setLong(1, campId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to check camp NPC marker: " + exception.getMessage());
            return false;
        }
    }



    public int getCampLevel(long campId) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("SELECT level FROM camps WHERE id = ? LIMIT 1")) {
            statement.setLong(1, campId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Math.max(1, rs.getInt("level")) : 1;
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to load camp level: " + exception.getMessage());
            return 1;
        }
    }


    public Optional<Location> getCampFoodChestLocation(long campId) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT food_chest_world, food_chest_x, food_chest_y, food_chest_z
                FROM camps
                WHERE id = ?
                LIMIT 1
                """)) {
            statement.setLong(1, campId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String worldName = rs.getString("food_chest_world");
                if (worldName == null || worldName.isBlank()) {
                    return Optional.empty();
                }
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    return Optional.empty();
                }
                return Optional.of(new Location(world, rs.getInt("food_chest_x"), rs.getInt("food_chest_y"), rs.getInt("food_chest_z")));
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to load camp food chest: " + exception.getMessage());
            return Optional.empty();
        }
    }

    public Optional<UUID> getCampLeaderUuid(long campId) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("SELECT owner_uuid FROM camps WHERE id = ? LIMIT 1")) {
            statement.setLong(1, campId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(UUID.fromString(rs.getString("owner_uuid"))) : Optional.empty();
            }
        } catch (SQLException | IllegalArgumentException exception) {
            plugin.getLogger().warning("Unable to load camp leader: " + exception.getMessage());
            return Optional.empty();
        }
    }

    public void addCampLeaderCoins(long campId, long amount) {
        if (amount <= 0L) {
            return;
        }
        Optional<UUID> leaderUuid = getCampLeaderUuid(campId);
        if (leaderUuid.isEmpty()) {
            return;
        }
        if (economy != null) {
            try {
                OfflinePlayer offlineLeader = Bukkit.getOfflinePlayer(leaderUuid.get());
                if (offlineLeader instanceof Player onlineLeader) {
                    economy.deposit(onlineLeader, amount, "camp quest reward");
                    return;
                }
                economy.deposit(offlineLeader, amount, "camp quest reward");
                return;
            } catch (SQLException exception) {
                plugin.getLogger().warning("Economy deposit failed for camp leader, using local SQLite fallback: " + exception.getMessage());
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Economy deposit runtime error for camp leader, using local SQLite fallback: " + exception.getMessage());
            }
        }
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE residents
                SET coins = coins + ?, updated_at = ?
                WHERE uuid = ?
                """)) {
            statement.setLong(1, amount);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, leaderUuid.get().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to add camp leader coins: " + exception.getMessage());
        }
    }

    public void addCampExperience(long campId, int amount) {
        if (amount <= 0) {
            return;
        }
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE camps
                SET experience = experience + ?,
                    level = CASE
                        WHEN experience + ? >= 1200 THEN 5
                        WHEN experience + ? >= 600 THEN 4
                        WHEN experience + ? >= 300 THEN 3
                        WHEN experience + ? >= 100 THEN 2
                        ELSE 1
                    END
                WHERE id = ?
                """)) {
            statement.setInt(1, amount);
            statement.setInt(2, amount);
            statement.setInt(3, amount);
            statement.setInt(4, amount);
            statement.setInt(5, amount);
            statement.setLong(6, campId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to add camp experience: " + exception.getMessage());
        }
    }

    public void addCampControlHp(long campId, int amount) {
        if (amount == 0) {
            return;
        }
        initializeStorage();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE camps
                SET control_hp = MAX(1, control_hp + ?),
                    control_max_hp = MAX(1, control_max_hp + ?)
                WHERE id = ?
                """)) {
            statement.setInt(1, amount);
            statement.setInt(2, amount);
            statement.setLong(3, campId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to add camp control HP: " + exception.getMessage());
        }
    }

    public void setCampUpgrade(long campId, String upgrade, boolean enabled) {
        String clean = upgrade == null ? "" : upgrade.trim().toLowerCase();
        String column;
        if (clean.equals("farm")) {
            column = "farm_upgrade";
        } else if (clean.equals("breaker")) {
            column = "breaker_upgrade";
        } else {
            plugin.getLogger().warning("Unknown camp upgrade reward: " + upgrade);
            return;
        }
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("UPDATE camps SET " + column + " = ? WHERE id = ?")) {
            statement.setInt(1, enabled ? 1 : 0);
            statement.setLong(2, campId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to set camp upgrade: " + exception.getMessage());
        }
    }

    public void saveCampNpcMarker(long campId, String world, int x, int y, int z) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE camps
                SET npc_marker_world = ?, npc_marker_x = ?, npc_marker_y = ?, npc_marker_z = ?
                WHERE id = ?
                """)) {
            statement.setString(1, world);
            statement.setInt(2, x);
            statement.setInt(3, y);
            statement.setInt(4, z);
            statement.setLong(5, campId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to save camp NPC marker: " + exception.getMessage());
        }
    }

    public void deleteCampNpcMarker(long campId) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE camps
                SET npc_marker_world = NULL, npc_marker_x = NULL, npc_marker_y = NULL, npc_marker_z = NULL
                WHERE id = ?
                """)) {
            statement.setLong(1, campId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to delete camp NPC marker: " + exception.getMessage());
        }
    }

    public void scanMissingCampNpcMarkersSafe() {
        try {
            scanMissingCampNpcMarkers();
        } catch (Exception exception) {
            plugin.getLogger().warning("Unable to scan camp NPC markers: " + exception.getMessage());
        }
    }

    private void scanMissingCampNpcMarkers() throws SQLException {
        for (CampScanTarget camp : campsWithoutMarker()) {
            if (loadMarkerFromProtectedBlocks(camp)) {
                continue;
            }
            if (!scanCampStructureChunks(camp)) {
                scanFallbackArea(camp);
            }
        }
    }

    private boolean loadMarkerFromProtectedBlocks(CampScanTarget camp) throws SQLException {
        if (!hasTable("protected_blocks") || !hasTable("structure_builds")) {
            return false;
        }
        if (!hasColumn("protected_blocks", "material")) {
            return false;
        }

        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT pb.world, pb.x, pb.y, pb.z
                FROM protected_blocks pb
                JOIN structure_builds sb ON sb.id = pb.build_id
                JOIN camps c ON c.id = ?
                WHERE pb.material = 'PINK_CONCRETE'
                  AND sb.structure_id = ?
                  AND sb.status <> 'DEMOLISHED'
                  AND (
                        sb.builder_uuid = c.owner_uuid
                        OR (sb.world = c.world AND sb.x = c.x AND sb.y = c.y AND sb.z = c.z)
                  )
                ORDER BY sb.id DESC
                LIMIT 1
                """)) {
            statement.setLong(1, camp.id());
            statement.setString(2, settings.campStructureId());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                String world = rs.getString("world");
                int x = rs.getInt("x");
                int y = rs.getInt("y");
                int z = rs.getInt("z");
                saveCampNpcMarker(camp.id(), world, x, y, z);
                removePinkMarkerBlockIfStillExists(world, x, y, z);
                plugin.getLogger().info("Saved camp NPC marker from protected_blocks for camp " + camp.id() + " at " + world + " " + x + " " + y + " " + z);
                return true;
            }
        }
    }

    private void removePinkMarkerBlockIfStillExists(String worldName, int x, int y, int z) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return;
        }
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() == Material.PINK_CONCRETE) {
            block.setType(Material.AIR, false);
        }
    }

    private boolean scanCampStructureChunks(CampScanTarget camp) throws SQLException {
        List<ChunkCoord> chunks = campChunks(camp.id());
        boolean scannedAnyChunk = false;
        for (ChunkCoord chunk : chunks) {
            World world = Bukkit.getWorld(chunk.world());
            if (world == null || !world.isChunkLoaded(chunk.chunkX(), chunk.chunkZ())) {
                continue;
            }
            scannedAnyChunk = true;
            if (scanChunkForMarker(camp.id(), world, chunk.chunkX(), chunk.chunkZ())) {
                return true;
            }
        }
        return scannedAnyChunk;
    }

    private boolean scanChunkForMarker(long campId, World world, int chunkX, int chunkZ) {
        int minY = Math.max(world.getMinHeight(), settings.structureMinY());
        int maxY = Math.min(world.getMaxHeight() - 1, settings.structureMaxY());
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int x = baseX; x < baseX + 16; x++) {
            for (int z = baseZ; z < baseZ + 16; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.PINK_CONCRETE) {
                        block.setType(Material.AIR, false);
                        saveCampNpcMarker(campId, world.getName(), x, y, z);
                        plugin.getLogger().info("Saved camp NPC marker from world scan for camp " + campId + " at " + world.getName() + " " + x + " " + y + " " + z);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean scanFallbackArea(CampScanTarget camp) {
        World world = Bukkit.getWorld(camp.world());
        if (world == null) {
            return false;
        }
        int minY = Math.max(world.getMinHeight(), settings.structureMinY());
        int maxY = Math.min(world.getMaxHeight() - 1, settings.structureMaxY());
        int radius = FALLBACK_SCAN_RADIUS_BLOCKS;
        for (int x = camp.x() - radius; x <= camp.x() + radius; x++) {
            for (int z = camp.z() - radius; z <= camp.z() + radius; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.PINK_CONCRETE) {
                        block.setType(Material.AIR, false);
                        saveCampNpcMarker(camp.id(), world.getName(), x, y, z);
                        plugin.getLogger().info("Saved camp NPC marker from fallback scan for camp " + camp.id() + " at " + world.getName() + " " + x + " " + y + " " + z);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private List<CampScanTarget> campsWithoutMarker() throws SQLException {
        List<CampScanTarget> camps = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT id, name, world, x, y, z
                FROM camps
                WHERE npc_marker_world IS NULL OR npc_marker_world = ''
                """)) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    camps.add(new CampScanTarget(
                            rs.getLong("id"),
                            rs.getString("name"),
                            rs.getString("world"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z")
                    ));
                }
            }
        }
        return camps;
    }

    private List<ChunkCoord> campChunks(long campId) throws SQLException {
        if (!hasTable("structure_chunks") || !hasTable("structure_builds")) {
            return List.of();
        }
        List<ChunkCoord> chunks = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT DISTINCT sc.world, sc.chunk_x, sc.chunk_z
                FROM structure_chunks sc
                JOIN structure_builds sb ON sb.id = sc.build_id
                JOIN camps c ON c.id = ?
                WHERE sb.status <> 'DEMOLISHED'
                  AND sb.structure_id = ?
                  AND (
                        sb.builder_uuid = c.owner_uuid
                        OR (sb.world = c.world AND sb.x = c.x AND sb.y = c.y AND sb.z = c.z)
                  )
                """)) {
            statement.setLong(1, campId);
            statement.setString(2, settings.campStructureId());
            try (ResultSet rs = statement.executeQuery()) {
                Set<String> seen = new HashSet<>();
                while (rs.next()) {
                    String world = rs.getString("world");
                    int chunkX = rs.getInt("chunk_x");
                    int chunkZ = rs.getInt("chunk_z");
                    String key = world + ":" + chunkX + ":" + chunkZ;
                    if (seen.add(key)) {
                        chunks.add(new ChunkCoord(world, chunkX, chunkZ));
                    }
                }
            }
        }
        return chunks;
    }

    private void ensureColumn(Connection connection, String table, String column, String type) throws SQLException {
        if (hasColumn(connection, table, column)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    private boolean hasColumn(String table, String column) throws SQLException {
        try (Connection connection = connect()) {
            return hasColumn(connection, table, column);
        }
    }

    private boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(" + table + ")")) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    if (column.equalsIgnoreCase(rs.getString("name"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasTable(String table) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM sqlite_master
                WHERE type = 'table' AND name = ?
                LIMIT 1
                """)) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private Connection connect() throws SQLException {
        Path databaseFile = settings.sqlitePath(plugin.getDataFolder().toPath());
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    private record CampScanTarget(long id, String name, String world, int x, int y, int z) {
    }

    private record ChunkCoord(String world, int chunkX, int chunkZ) {
    }
}
