package com.avrgaming.civcraft.modern.storage;

import com.avrgaming.civcraft.modern.build.BlockBackup;
import com.avrgaming.civcraft.modern.build.BlockPlacement;
import com.avrgaming.civcraft.modern.build.StructureChunkCoord;
import com.avrgaming.civcraft.modern.config.ModernCivCraftSettings;
import com.avrgaming.civcraft.modern.domain.CampRecord;
import com.avrgaming.civcraft.modern.domain.CivRecord;
import com.avrgaming.civcraft.modern.domain.ResidentProfile;
import com.avrgaming.civcraft.modern.domain.TownClaimRecord;
import com.avrgaming.civcraft.modern.domain.TownRecord;
import com.avrgaming.civcraft.modern.research.ActiveResearch;
import com.avrgaming.civcraft.modern.research.ResearchProgress;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

public final class StorageBootstrap implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ModernCivCraftSettings settings;
    private Connection connection;

    public record StructureBuildView(
            long id,
            Long townId,
            String townName,
            String structureId,
            String displayName,
            String status,
            String world,
            int x,
            int y,
            int z,
            int chunkX,
            int chunkZ,
            long cost,
            double totalHammers,
            double hammersPerHour,
            long startedAt,
            long finishAt,
            long createdAt
    ) {
        public double progressPercent(long now) {
            if (status.equalsIgnoreCase("COMPLETE")) {
                return 100.0;
            }
            if (status.equalsIgnoreCase("CANCELLED")) {
                return 0.0;
            }
            if (finishAt <= startedAt || totalHammers <= 0.0) {
                return 100.0;
            }
            double progress = ((now - startedAt) / (double) (finishAt - startedAt)) * 100.0;
            return Math.max(0.0, Math.min(100.0, progress));
        }

        public long remainingMillis(long now) {
            if (status.equalsIgnoreCase("COMPLETE") || status.equalsIgnoreCase("CANCELLED") || finishAt <= now) {
                return 0L;
            }
            return finishAt - now;
        }
    }

    public StorageBootstrap(JavaPlugin plugin, ModernCivCraftSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public void initializeLocalStorage() {
        if (!settings.storageType().equalsIgnoreCase("sqlite")) {
            plugin.getLogger().warning("Only local SQLite bootstrap is implemented in this port foundation. Requested: " + settings.storageType());
            return;
        }

        Path dataFolder = plugin.getDataFolder().toPath();
        Path databaseFile = settings.sqlitePath(dataFolder);
        try {
            Files.createDirectories(dataFolder);
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
            this.connection.setAutoCommit(true);
            applySqlitePragmas(connection);
            createBootstrapSchema();
            migrateBootstrapSchema();
            normalizeMoneyValues();
            plugin.getLogger().info("Local CivCraft SQLite storage ready: " + databaseFile);
        } catch (IOException | SQLException exception) {
            plugin.getLogger().severe("Unable to initialize local CivCraft storage: " + exception.getMessage());
        }
    }

    private void applySqlitePragmas(Connection target) throws SQLException {
        try (Statement statement = target.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
        }
    }

    private void createBootstrapSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS civcraft_meta (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS residents (
                        uuid TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        coins INTEGER NOT NULL DEFAULT 0,
                        civ_id INTEGER,
                        town_id INTEGER,
                        camp_id INTEGER,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS camps (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL UNIQUE COLLATE NOCASE,
                        owner_uuid TEXT NOT NULL UNIQUE,
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        hitpoints INTEGER NOT NULL,
                        firepoints_hours INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(owner_uuid) REFERENCES residents(uuid) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS civilizations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL UNIQUE COLLATE NOCASE,
                        tag TEXT NOT NULL DEFAULT '',
                        leader_uuid TEXT NOT NULL,
                        coins INTEGER NOT NULL DEFAULT 0,
                        government TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(leader_uuid) REFERENCES residents(uuid) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS towns (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL UNIQUE COLLATE NOCASE,
                        civ_id INTEGER NOT NULL,
                        mayor_uuid TEXT NOT NULL,
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        level INTEGER NOT NULL DEFAULT 1,
                        coins INTEGER NOT NULL DEFAULT 0,
                        hammers_per_hour REAL NOT NULL DEFAULT 100.0,
                        beakers_per_hour REAL NOT NULL DEFAULT 0,
                        money_per_hour REAL NOT NULL DEFAULT 0,
                        base_happiness INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(civ_id) REFERENCES civilizations(id) ON DELETE CASCADE,
                        FOREIGN KEY(mayor_uuid) REFERENCES residents(uuid) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS town_claims (
                        world TEXT NOT NULL,
                        chunk_x INTEGER NOT NULL,
                        chunk_z INTEGER NOT NULL,
                        town_id INTEGER NOT NULL,
                        civ_id INTEGER NOT NULL,
                        claimed_by_uuid TEXT NOT NULL,
                        claimed_at INTEGER NOT NULL,
                        PRIMARY KEY(world, chunk_x, chunk_z),
                        FOREIGN KEY(town_id) REFERENCES towns(id) ON DELETE CASCADE,
                        FOREIGN KEY(civ_id) REFERENCES civilizations(id) ON DELETE CASCADE,
                        FOREIGN KEY(claimed_by_uuid) REFERENCES residents(uuid) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS structure_builds (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        builder_uuid TEXT NOT NULL,
                        town_id INTEGER,
                        type TEXT NOT NULL,
                        template TEXT NOT NULL,
                        direction TEXT NOT NULL,
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        template_path TEXT NOT NULL,
                        queued_blocks INTEGER NOT NULL,
                        structure_id TEXT,
                        display_name TEXT,
                        cost INTEGER NOT NULL DEFAULT 0,
                        max_hitpoints INTEGER NOT NULL DEFAULT 1,
                        total_hammers REAL NOT NULL DEFAULT 0,
                        hammers_per_hour REAL NOT NULL DEFAULT 0,
                        production_hammers_per_hour REAL NOT NULL DEFAULT 0,
                        beakers_per_hour REAL NOT NULL DEFAULT 0,
                        money_per_hour REAL NOT NULL DEFAULT 0,
                        happiness INTEGER NOT NULL DEFAULT 0,
                        started_at INTEGER,
                        finish_at INTEGER,
                        status TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        completed_at INTEGER,
                        FOREIGN KEY(builder_uuid) REFERENCES residents(uuid) ON DELETE CASCADE,
                        FOREIGN KEY(town_id) REFERENCES towns(id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS protected_blocks (
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        build_id INTEGER NOT NULL,
                        material TEXT NOT NULL,
                        PRIMARY KEY(world, x, y, z),
                        FOREIGN KEY(build_id) REFERENCES structure_builds(id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS structure_chunks (
                        world TEXT NOT NULL,
                        chunk_x INTEGER NOT NULL,
                        chunk_z INTEGER NOT NULL,
                        build_id INTEGER NOT NULL,
                        town_id INTEGER,
                        created_at INTEGER NOT NULL,
                        PRIMARY KEY(world, chunk_x, chunk_z),
                        FOREIGN KEY(build_id) REFERENCES structure_builds(id) ON DELETE CASCADE,
                        FOREIGN KEY(town_id) REFERENCES towns(id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS structure_block_backups (
                        build_id INTEGER NOT NULL,
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        block_data TEXT NOT NULL,
                        PRIMARY KEY(build_id, world, x, y, z),
                        FOREIGN KEY(build_id) REFERENCES structure_builds(id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS civ_technologies (
                        civ_id INTEGER NOT NULL,
                        tech_id TEXT NOT NULL,
                        researched_at INTEGER NOT NULL,
                        PRIMARY KEY(civ_id, tech_id),
                        FOREIGN KEY(civ_id) REFERENCES civilizations(id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS civ_research (
                        civ_id INTEGER PRIMARY KEY,
                        tech_id TEXT NOT NULL,
                        progress REAL NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL,
                        FOREIGN KEY(civ_id) REFERENCES civilizations(id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_protected_blocks_build ON protected_blocks(build_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_structure_block_backups_build ON structure_block_backups(build_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_structure_chunks_build ON structure_chunks(build_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_structure_builds_town ON structure_builds(town_id)");
            statement.executeUpdate("""
                    INSERT INTO civcraft_meta(key, value)
                    VALUES('schema_version', 'modern-playable-5')
                    ON CONFLICT(key) DO UPDATE SET value = excluded.value
                    """);
        }
    }

    private void migrateBootstrapSchema() throws SQLException {
        addColumnIfMissing("civilizations", "tag", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing("towns", "hammers_per_hour", "REAL NOT NULL DEFAULT 100.0");
        addColumnIfMissing("towns", "beakers_per_hour", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing("towns", "money_per_hour", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing("towns", "base_happiness", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("structure_builds", "town_id", "INTEGER");
        addColumnIfMissing("structure_builds", "total_hammers", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing("structure_builds", "hammers_per_hour", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing("structure_builds", "production_hammers_per_hour", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing("structure_builds", "beakers_per_hour", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing("structure_builds", "money_per_hour", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing("structure_builds", "happiness", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("structure_builds", "started_at", "INTEGER");
        addColumnIfMissing("structure_builds", "finish_at", "INTEGER");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS structure_chunks (
                        world TEXT NOT NULL,
                        chunk_x INTEGER NOT NULL,
                        chunk_z INTEGER NOT NULL,
                        build_id INTEGER NOT NULL,
                        town_id INTEGER,
                        created_at INTEGER NOT NULL,
                        PRIMARY KEY(world, chunk_x, chunk_z),
                        FOREIGN KEY(build_id) REFERENCES structure_builds(id) ON DELETE CASCADE,
                        FOREIGN KEY(town_id) REFERENCES towns(id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS structure_block_backups (
                        build_id INTEGER NOT NULL,
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        block_data TEXT NOT NULL,
                        PRIMARY KEY(build_id, world, x, y, z),
                        FOREIGN KEY(build_id) REFERENCES structure_builds(id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_structure_chunks_build ON structure_chunks(build_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_structure_builds_town ON structure_builds(town_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_structure_block_backups_build ON structure_block_backups(build_id)");
        }
    }

    private void addColumnIfMissing(String table, String column, String definition) throws SQLException {
        if (hasColumn(table, column)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private boolean hasColumn(String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void normalizeMoneyValues() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE residents SET coins = CAST(ROUND(coins) AS INTEGER)");
            statement.executeUpdate("UPDATE civilizations SET coins = CAST(ROUND(coins) AS INTEGER)");
            statement.executeUpdate("UPDATE towns SET coins = CAST(ROUND(coins) AS INTEGER)");
            statement.executeUpdate("UPDATE structure_builds SET cost = CAST(ROUND(cost) AS INTEGER)");
        }
    }

    public void warmupAsync() {
        if (connection == null) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Path databaseFile = settings.sqlitePath(plugin.getDataFolder().toPath());
            try (Connection warmup = DriverManager.getConnection("jdbc:sqlite:" + databaseFile); Statement statement = warmup.createStatement()) {
                applySqlitePragmas(warmup);
                statement.executeQuery("SELECT 1").close();
            } catch (SQLException exception) {
                plugin.getLogger().warning("CivCraft SQLite warmup failed: " + exception.getMessage());
            }
        });
    }

    public boolean isReady() {
        return connection != null;
    }

    public ResidentProfile getOrCreateResident(UUID uuid, String name, double startingCoins) throws SQLException {
        Optional<ResidentProfile> existing = findResident(uuid);
        if (existing.isPresent()) {
            updateResidentName(uuid, name);
            return findResident(uuid).orElseThrow();
        }
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO residents(uuid, name, coins, created_at, updated_at) VALUES(?, ?, ?, ?, ?)")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, name);
            statement.setLong(3, moneyToLong(startingCoins));
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
        return findResident(uuid).orElseThrow();
    }

    public Optional<ResidentProfile> findResident(UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM residents WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapResident(rs));
            }
        }
    }

    public Optional<CampRecord> findCampByOwner(UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM camps WHERE owner_uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapCamp(rs)) : Optional.empty();
            }
        }
    }

    public Optional<CivRecord> findCiv(long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM civilizations WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapCiv(rs)) : Optional.empty();
            }
        }
    }

    public Optional<TownRecord> findTown(long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM towns WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapTown(rs)) : Optional.empty();
            }
        }
    }

    public void depositTown(long townId, double amount) throws SQLException {
        long money = moneyToLong(amount);
        try (PreparedStatement statement = connection.prepareStatement("UPDATE towns SET coins = coins + ? WHERE id = ?")) {
            statement.setLong(1, money);
            statement.setLong(2, townId);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Town does not exist: " + townId);
            }
        }
    }

    public void withdrawTown(long townId, double amount) throws SQLException {
        long money = moneyToLong(amount);
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement check = connection.prepareStatement("SELECT coins FROM towns WHERE id = ?")) {
                check.setLong(1, townId);
                try (ResultSet rs = check.executeQuery()) {
                    if (!rs.next() || rs.getLong("coins") < money) {
                        throw new SQLException("Town does not have enough coins");
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("UPDATE towns SET coins = coins - ? WHERE id = ?")) {
                statement.setLong(1, money);
                statement.setLong(2, townId);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public void depositCiv(long civId, double amount) throws SQLException {
        long money = moneyToLong(amount);
        try (PreparedStatement statement = connection.prepareStatement("UPDATE civilizations SET coins = coins + ? WHERE id = ?")) {
            statement.setLong(1, money);
            statement.setLong(2, civId);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Civilization does not exist: " + civId);
            }
        }
    }

    public void withdrawCiv(long civId, double amount) throws SQLException {
        long money = moneyToLong(amount);
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement check = connection.prepareStatement("SELECT coins FROM civilizations WHERE id = ?")) {
                check.setLong(1, civId);
                try (ResultSet rs = check.executeQuery()) {
                    if (!rs.next() || rs.getLong("coins") < money) {
                        throw new SQLException("Civilization does not have enough coins");
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("UPDATE civilizations SET coins = coins - ? WHERE id = ?")) {
                statement.setLong(1, money);
                statement.setLong(2, civId);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public boolean hasTownNear(Location location, double distance) throws SQLException {
        double squared = distance * distance;
        try (PreparedStatement statement = connection.prepareStatement("SELECT world, x, y, z FROM towns WHERE world = ?")) {
            statement.setString(1, location.getWorld().getName());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    double dx = rs.getInt("x") - location.getBlockX();
                    double dy = rs.getInt("y") - location.getBlockY();
                    double dz = rs.getInt("z") - location.getBlockZ();
                    if ((dx * dx) + (dy * dy) + (dz * dz) < squared) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public CampRecord createCamp(UUID owner, String name, Location location, double cost, int hitpoints, int firepointsHours) throws SQLException {
        connection.setAutoCommit(false);
        try {
            withdrawResident(owner, cost);
            long now = System.currentTimeMillis();
            long campId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO camps(name, owner_uuid, world, x, y, z, hitpoints, firepoints_hours, created_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, name);
                statement.setString(2, owner.toString());
                statement.setString(3, location.getWorld().getName());
                statement.setInt(4, location.getBlockX());
                statement.setInt(5, location.getBlockY());
                statement.setInt(6, location.getBlockZ());
                statement.setInt(7, hitpoints);
                statement.setInt(8, firepointsHours);
                statement.setLong(9, now);
                statement.executeUpdate();
                campId = generatedId(statement);
            }
            try (PreparedStatement statement = connection.prepareStatement("UPDATE residents SET camp_id = ?, updated_at = ? WHERE uuid = ?")) {
                statement.setLong(1, campId);
                statement.setLong(2, now);
                statement.setString(3, owner.toString());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO camp_members(camp_id, uuid, name, role, joined_at) VALUES(?, ?, ?, 'LEADER', ?)")) {
                statement.setLong(1, campId);
                statement.setString(2, owner.toString());
                statement.setString(3, name);
                statement.setLong(4, now);
                statement.executeUpdate();
            }
            connection.commit();
            return findCampByOwner(owner).orElseThrow();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public CivRecord createCivilization(UUID leader, String name, double cost, String government) throws SQLException {
        return createCivilization(leader, name, "", cost, government);
    }

    public CivRecord createCivilization(UUID leader, String name, String tag, double cost, String government) throws SQLException {
        connection.setAutoCommit(false);
        try {
            withdrawResident(leader, cost);
            long now = System.currentTimeMillis();
            long civId;
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO civilizations(name, tag, leader_uuid, government, created_at) VALUES(?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, name);
                statement.setString(2, tag == null ? "" : tag.toUpperCase(java.util.Locale.ROOT));
                statement.setString(3, leader.toString());
                statement.setString(4, government);
                statement.setLong(5, now);
                statement.executeUpdate();
                civId = generatedId(statement);
            }
            try (PreparedStatement statement = connection.prepareStatement("UPDATE residents SET civ_id = ?, updated_at = ? WHERE uuid = ?")) {
                statement.setLong(1, civId);
                statement.setLong(2, now);
                statement.setString(3, leader.toString());
                statement.executeUpdate();
            }
            connection.commit();
            return findCiv(civId).orElseThrow();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public TownRecord createTown(UUID mayor, long civId, String name, Location location, double cost, double hammersPerHour) throws SQLException {
        connection.setAutoCommit(false);
        try {
            withdrawResident(mayor, cost);
            long now = System.currentTimeMillis();
            long townId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO towns(name, civ_id, mayor_uuid, world, x, y, z, hammers_per_hour, created_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, name);
                statement.setLong(2, civId);
                statement.setString(3, mayor.toString());
                statement.setString(4, location.getWorld().getName());
                statement.setInt(5, location.getBlockX());
                statement.setInt(6, location.getBlockY());
                statement.setInt(7, location.getBlockZ());
                statement.setDouble(8, Math.max(0.0, hammersPerHour));
                statement.setLong(9, now);
                statement.executeUpdate();
                townId = generatedId(statement);
            }
            try (PreparedStatement statement = connection.prepareStatement("UPDATE residents SET town_id = ?, updated_at = ? WHERE uuid = ?")) {
                statement.setLong(1, townId);
                statement.setLong(2, now);
                statement.setString(3, mayor.toString());
                statement.executeUpdate();
            }
            connection.commit();
            return findTown(townId).orElseThrow();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public Optional<TownClaimRecord> findTownClaim(String world, int chunkX, int chunkZ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM town_claims WHERE world = ? AND chunk_x = ? AND chunk_z = ?")) {
            statement.setString(1, world);
            statement.setInt(2, chunkX);
            statement.setInt(3, chunkZ);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapTownClaim(rs)) : Optional.empty();
            }
        }
    }

    public int townClaimCount(long townId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) AS count FROM town_claims WHERE town_id = ?")) {
            statement.setLong(1, townId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt("count") : 0;
            }
        }
    }

    public TownClaimRecord claimTownChunk(UUID claimant, long townId, long civId, String world, int chunkX, int chunkZ, double cost) throws SQLException {
        connection.setAutoCommit(false);
        try {
            withdrawResident(claimant, cost);
            try (PreparedStatement check = connection.prepareStatement("SELECT town_id FROM town_claims WHERE world = ? AND chunk_x = ? AND chunk_z = ?")) {
                check.setString(1, world);
                check.setInt(2, chunkX);
                check.setInt(3, chunkZ);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        throw new SQLException("Chunk is already claimed by town #" + rs.getLong("town_id"));
                    }
                }
            }
            long now = System.currentTimeMillis();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO town_claims(world, chunk_x, chunk_z, town_id, civ_id, claimed_by_uuid, claimed_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, world);
                statement.setInt(2, chunkX);
                statement.setInt(3, chunkZ);
                statement.setLong(4, townId);
                statement.setLong(5, civId);
                statement.setString(6, claimant.toString());
                statement.setLong(7, now);
                statement.executeUpdate();
            }
            connection.commit();
            return new TownClaimRecord(world, chunkX, chunkZ, townId, civId, claimant, now);
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public void unclaimTownChunk(long townId, String world, int chunkX, int chunkZ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM town_claims WHERE town_id = ? AND world = ? AND chunk_x = ? AND chunk_z = ?")) {
            statement.setLong(1, townId);
            statement.setString(2, world);
            statement.setInt(3, chunkX);
            statement.setInt(4, chunkZ);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("This chunk is not claimed by your town");
            }
        }
    }

    public long recordStructureBuild(UUID builder, Long townId, String type, String template, String direction, Location origin, String templatePath, int queuedBlocks, String structureId, String displayName, double cost, int maxHitpoints, double totalHammers, double hammersPerHour, double productionHammersPerHour, double beakersPerHour, double moneyPerHour, int happiness, long startedAt, long finishAt) throws SQLException {
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO structure_builds(builder_uuid, town_id, type, template, direction, world, x, y, z, template_path, queued_blocks, structure_id, display_name, cost, max_hitpoints, total_hammers, hammers_per_hour, production_hammers_per_hour, beakers_per_hour, money_per_hour, happiness, started_at, finish_at, status, created_at)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, builder.toString());
            if (townId == null) {
                statement.setNull(2, Types.INTEGER);
            } else {
                statement.setLong(2, townId);
            }
            statement.setString(3, type);
            statement.setString(4, template);
            statement.setString(5, direction);
            statement.setString(6, origin.getWorld().getName());
            statement.setInt(7, origin.getBlockX());
            statement.setInt(8, origin.getBlockY());
            statement.setInt(9, origin.getBlockZ());
            statement.setString(10, templatePath);
            statement.setInt(11, queuedBlocks);
            statement.setString(12, structureId);
            statement.setString(13, displayName);
            statement.setLong(14, moneyToLong(cost));
            statement.setInt(15, maxHitpoints);
            statement.setDouble(16, Math.max(0.0, totalHammers));
            statement.setDouble(17, Math.max(0.0, hammersPerHour));
            statement.setDouble(18, Math.max(0.0, productionHammersPerHour));
            statement.setDouble(19, Math.max(0.0, beakersPerHour));
            statement.setDouble(20, Math.max(0.0, moneyPerHour));
            statement.setInt(21, Math.max(-5, Math.min(5, happiness)));
            statement.setLong(22, startedAt);
            statement.setLong(23, finishAt);
            statement.setLong(24, now);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    public boolean hasStructureChunkOverlap(Collection<StructureChunkCoord> chunks) throws SQLException {
        if (chunks == null || chunks.isEmpty()) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT build_id FROM structure_chunks WHERE world = ? AND chunk_x = ? AND chunk_z = ? LIMIT 1")) {
            for (StructureChunkCoord chunk : chunks) {
                statement.setString(1, chunk.world());
                statement.setInt(2, chunk.chunkX());
                statement.setInt(3, chunk.chunkZ());
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean hasActiveStructureBuild(long townId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM structure_builds
                WHERE town_id = ? AND status NOT IN ('COMPLETE', 'CANCELLED', 'DEMOLISHED')
                LIMIT 1
                """)) {
            statement.setLong(1, townId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    public Optional<StructureBuildView> findActiveStructureBuildForTown(long townId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT sb.*, t.name AS town_name
                FROM structure_builds sb
                LEFT JOIN towns t ON t.id = sb.town_id
                WHERE sb.town_id = ? AND sb.status NOT IN ('COMPLETE', 'CANCELLED', 'DEMOLISHED')
                ORDER BY sb.created_at DESC
                LIMIT 1
                """)) {
            statement.setLong(1, townId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapStructureBuildView(rs, null, null)) : Optional.empty();
            }
        }
    }

    public Optional<StructureBuildView> findStructureBuildAtChunk(String world, int chunkX, int chunkZ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT sb.*, t.name AS town_name, sc.chunk_x AS info_chunk_x, sc.chunk_z AS info_chunk_z
                FROM structure_chunks sc
                JOIN structure_builds sb ON sb.id = sc.build_id
                LEFT JOIN towns t ON t.id = sb.town_id
                WHERE sc.world = ? AND sc.chunk_x = ? AND sc.chunk_z = ?
                ORDER BY sb.created_at DESC
                LIMIT 1
                """)) {
            statement.setString(1, world);
            statement.setInt(2, chunkX);
            statement.setInt(3, chunkZ);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapStructureBuildView(rs, chunkX, chunkZ)) : Optional.empty();
            }
        }
    }

    public void cancelStructureBuild(long buildId) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM protected_blocks WHERE build_id = ?")) {
                statement.setLong(1, buildId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM structure_chunks WHERE build_id = ?")) {
                statement.setLong(1, buildId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("UPDATE structure_builds SET status = 'CANCELLED', completed_at = ? WHERE id = ? AND status NOT IN ('COMPLETE', 'CANCELLED', 'DEMOLISHED')")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setLong(2, buildId);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public void recordStructureChunks(long buildId, Long townId, Collection<StructureChunkCoord> chunks) throws SQLException {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO structure_chunks(world, chunk_x, chunk_z, build_id, town_id, created_at)
                VALUES(?, ?, ?, ?, ?, ?)
                """)) {
            for (StructureChunkCoord chunk : chunks) {
                statement.setString(1, chunk.world());
                statement.setInt(2, chunk.chunkX());
                statement.setInt(3, chunk.chunkZ());
                statement.setLong(4, buildId);
                if (townId == null) {
                    statement.setNull(5, Types.INTEGER);
                } else {
                    statement.setLong(5, townId);
                }
                statement.setLong(6, now);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public void completeStructureBuild(long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE structure_builds SET status = 'COMPLETE', completed_at = ? WHERE id = ? AND status <> 'CANCELLED'")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setLong(2, id);
            statement.executeUpdate();
        }
    }

    public void recordProtectedBlocks(long buildId, Collection<BlockPlacement> placements) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR REPLACE INTO protected_blocks(world, x, y, z, build_id, material)
                VALUES(?, ?, ?, ?, ?, ?)
                """)) {
            for (BlockPlacement placement : placements) {
                statement.setString(1, placement.world());
                statement.setInt(2, placement.x());
                statement.setInt(3, placement.y());
                statement.setInt(4, placement.z());
                statement.setLong(5, buildId);
                statement.setString(6, placement.material());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public void recordProtectedBlocksAndCompleteBuild(long buildId, Collection<BlockPlacement> placements) throws SQLException {
        Path databaseFile = settings.sqlitePath(plugin.getDataFolder().toPath());
        try (Connection writeConnection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            applySqlitePragmas(writeConnection);
            writeConnection.setAutoCommit(false);
            try (
                    PreparedStatement insert = writeConnection.prepareStatement("""
                            INSERT OR REPLACE INTO protected_blocks(world, x, y, z, build_id, material)
                            VALUES(?, ?, ?, ?, ?, ?)
                            """);
                    PreparedStatement complete = writeConnection.prepareStatement("UPDATE structure_builds SET status = 'COMPLETE', completed_at = ? WHERE id = ? AND status <> 'CANCELLED'")
            ) {
                int batchSize = Math.max(1, settings.maxDatabaseWritesPerTick());
                int pending = 0;
                for (BlockPlacement placement : placements) {
                    insert.setString(1, placement.world());
                    insert.setInt(2, placement.x());
                    insert.setInt(3, placement.y());
                    insert.setInt(4, placement.z());
                    insert.setLong(5, buildId);
                    insert.setString(6, placement.material());
                    insert.addBatch();
                    pending++;
                    if (pending >= batchSize) {
                        insert.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    insert.executeBatch();
                }
                complete.setLong(1, System.currentTimeMillis());
                complete.setLong(2, buildId);
                complete.executeUpdate();
                writeConnection.commit();
            } catch (SQLException exception) {
                writeConnection.rollback();
                throw exception;
            } finally {
                writeConnection.setAutoCommit(true);
            }
        }
    }

    public void recordProtectedBlocksBackupsAndCompleteBuild(long buildId, Collection<BlockPlacement> placements, Collection<BlockBackup> backups) throws SQLException {
        Path databaseFile = settings.sqlitePath(plugin.getDataFolder().toPath());
        try (Connection writeConnection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            applySqlitePragmas(writeConnection);
            writeConnection.setAutoCommit(false);
            try (
                    PreparedStatement backupInsert = writeConnection.prepareStatement("""
                            INSERT OR IGNORE INTO structure_block_backups(build_id, world, x, y, z, block_data)
                            VALUES(?, ?, ?, ?, ?, ?)
                            """);
                    PreparedStatement insert = writeConnection.prepareStatement("""
                            INSERT OR REPLACE INTO protected_blocks(world, x, y, z, build_id, material)
                            VALUES(?, ?, ?, ?, ?, ?)
                            """);
                    PreparedStatement complete = writeConnection.prepareStatement("UPDATE structure_builds SET status = 'COMPLETE', completed_at = ? WHERE id = ? AND status <> 'CANCELLED'")
            ) {
                int batchSize = Math.max(1, settings.maxDatabaseWritesPerTick());
                int pendingBackups = 0;
                for (BlockBackup backup : backups) {
                    backupInsert.setLong(1, buildId);
                    backupInsert.setString(2, backup.world());
                    backupInsert.setInt(3, backup.x());
                    backupInsert.setInt(4, backup.y());
                    backupInsert.setInt(5, backup.z());
                    backupInsert.setString(6, backup.blockData());
                    backupInsert.addBatch();
                    pendingBackups++;
                    if (pendingBackups >= batchSize) {
                        backupInsert.executeBatch();
                        pendingBackups = 0;
                    }
                }
                if (pendingBackups > 0) {
                    backupInsert.executeBatch();
                }

                int pending = 0;
                for (BlockPlacement placement : placements) {
                    insert.setString(1, placement.world());
                    insert.setInt(2, placement.x());
                    insert.setInt(3, placement.y());
                    insert.setInt(4, placement.z());
                    insert.setLong(5, buildId);
                    insert.setString(6, placement.material());
                    insert.addBatch();
                    pending++;
                    if (pending >= batchSize) {
                        insert.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    insert.executeBatch();
                }
                complete.setLong(1, System.currentTimeMillis());
                complete.setLong(2, buildId);
                complete.executeUpdate();
                writeConnection.commit();
            } catch (SQLException exception) {
                writeConnection.rollback();
                throw exception;
            } finally {
                writeConnection.setAutoCommit(true);
            }
        }
    }

    public List<BlockBackup> loadStructureBlockBackups(long buildId) throws SQLException {
        List<BlockBackup> backups = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT world, x, y, z, block_data
                FROM structure_block_backups
                WHERE build_id = ?
                ORDER BY y DESC
                """)) {
            statement.setLong(1, buildId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    backups.add(new BlockBackup(rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"), rs.getString("block_data")));
                }
            }
        }
        return backups;
    }

    public void demolishStructureBuild(long buildId) throws SQLException {
        connection.setAutoCommit(false);
        try {
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
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public boolean isProtectedBlock(String world, int x, int y, int z) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM protected_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
            statement.setString(1, world);
            statement.setInt(2, x);
            statement.setInt(3, y);
            statement.setInt(4, z);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    public Set<String> researchedTechs(long civId) throws SQLException {
        Set<String> techs = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT tech_id FROM civ_technologies WHERE civ_id = ?")) {
            statement.setLong(1, civId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    techs.add(rs.getString("tech_id"));
                }
            }
        }
        return techs;
    }

    public Optional<ResearchProgress> researchProgress(long civId, double requiredBeakers) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT tech_id, progress FROM civ_research WHERE civ_id = ?")) {
            statement.setLong(1, civId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ResearchProgress(civId, rs.getString("tech_id"), rs.getDouble("progress"), requiredBeakers));
            }
        }
    }

    public void startResearch(long civId, String techId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO civ_research(civ_id, tech_id, progress, updated_at)
                VALUES(?, ?, 0, ?)
                ON CONFLICT(civ_id) DO UPDATE SET tech_id = excluded.tech_id, progress = 0, updated_at = excluded.updated_at
                """)) {
            statement.setLong(1, civId);
            statement.setString(2, techId);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    public List<ActiveResearch> activeResearches() throws SQLException {
        List<ActiveResearch> researches = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT civ_id, tech_id, progress FROM civ_research")) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    researches.add(new ActiveResearch(rs.getLong("civ_id"), rs.getString("tech_id"), rs.getDouble("progress")));
                }
            }
        }
        return researches;
    }

    public boolean addResearchBeakers(long civId, String techId, double beakers, double requiredBeakers) throws SQLException {
        if (Double.isNaN(beakers) || Double.isInfinite(beakers) || beakers < 0) {
            throw new SQLException("Invalid beaker amount: " + beakers);
        }
        connection.setAutoCommit(false);
        try {
            double progress;
            try (PreparedStatement read = connection.prepareStatement("SELECT progress FROM civ_research WHERE civ_id = ? AND tech_id = ?")) {
                read.setLong(1, civId);
                read.setString(2, techId);
                try (ResultSet rs = read.executeQuery()) {
                    if (!rs.next()) {
                        connection.rollback();
                        return false;
                    }
                    progress = rs.getDouble("progress") + beakers;
                }
            }
            if (progress >= requiredBeakers) {
                try (PreparedStatement insert = connection.prepareStatement("INSERT OR IGNORE INTO civ_technologies(civ_id, tech_id, researched_at) VALUES(?, ?, ?)")) {
                    insert.setLong(1, civId);
                    insert.setString(2, techId);
                    insert.setLong(3, System.currentTimeMillis());
                    insert.executeUpdate();
                }
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM civ_research WHERE civ_id = ?")) {
                    delete.setLong(1, civId);
                    delete.executeUpdate();
                }
                connection.commit();
                return true;
            }
            try (PreparedStatement update = connection.prepareStatement("UPDATE civ_research SET progress = ?, updated_at = ? WHERE civ_id = ? AND tech_id = ?")) {
                update.setDouble(1, progress);
                update.setLong(2, System.currentTimeMillis());
                update.setLong(3, civId);
                update.setString(4, techId);
                update.executeUpdate();
            }
            connection.commit();
            return false;
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }


    public double civBeakersPerHour(long civId) throws SQLException {
        List<TownBaseProduction> towns = townBaseProductions(civId);
        double total = 0.0;
        for (TownBaseProduction town : towns) {
            total += townProduction(town.id(), town.baseHammersPerHour(), town.baseBeakersPerHour(), town.baseMoneyPerHour(), town.baseHappiness()).beakersPerHour();
        }
        return total;
    }

    public double civMoneyPerHour(long civId) throws SQLException {
        List<TownBaseProduction> towns = townBaseProductions(civId);
        double total = 0.0;
        for (TownBaseProduction town : towns) {
            total += townProduction(town.id(), town.baseHammersPerHour(), town.baseBeakersPerHour(), town.baseMoneyPerHour(), town.baseHappiness()).moneyPerHour();
        }
        return total;
    }

    private List<TownBaseProduction> townBaseProductions(long civId) throws SQLException {
        List<TownBaseProduction> towns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, hammers_per_hour, beakers_per_hour, money_per_hour, base_happiness FROM towns WHERE civ_id = ?")) {
            statement.setLong(1, civId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    towns.add(new TownBaseProduction(rs.getLong("id"), rs.getDouble("hammers_per_hour"), rs.getDouble("beakers_per_hour"), rs.getDouble("money_per_hour"), rs.getInt("base_happiness")));
                }
            }
        }
        return towns;
    }

    private TownProduction townProduction(long townId, double baseHammersPerHour, double baseBeakersPerHour, double baseMoneyPerHour, int baseHappiness) throws SQLException {
        double structureHammers = 0.0;
        double beakers = 0.0;
        double money = 0.0;
        int structureHappiness = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT
                    COALESCE(SUM(production_hammers_per_hour), 0) AS hammers,
                    COALESCE(SUM(beakers_per_hour), 0) AS beakers,
                    COALESCE(SUM(money_per_hour), 0) AS money,
                    COALESCE(SUM(happiness), 0) AS happiness
                FROM structure_builds
                WHERE town_id = ? AND status = 'COMPLETE'
                """)) {
            statement.setLong(1, townId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    structureHammers = rs.getDouble("hammers");
                    beakers = rs.getDouble("beakers");
                    money = rs.getDouble("money");
                    structureHappiness = rs.getInt("happiness");
                }
            }
        }
        int happiness = clampHappiness(baseHappiness + structureHappiness);
        double multiplier = happinessMultiplier(happiness);
        return new TownProduction(
                (Math.max(0.0, baseHammersPerHour) + Math.max(0.0, structureHammers)) * multiplier,
                (Math.max(0.0, baseBeakersPerHour) + Math.max(0.0, beakers)) * multiplier,
                (Math.max(0.0, baseMoneyPerHour) + Math.max(0.0, money)) * multiplier,
                happiness,
                happinessState(happiness),
                multiplier
        );
    }

    private int clampHappiness(int value) {
        return Math.max(-5, Math.min(5, value));
    }

    private double happinessMultiplier(int happiness) {
        if (happiness <= -5) {
            return 0.50;
        }
        if (happiness <= -3) {
            return 0.65;
        }
        if (happiness <= -1) {
            return 0.85;
        }
        if (happiness == 0) {
            return 1.00;
        }
        if (happiness <= 2) {
            return 1.15;
        }
        if (happiness <= 4) {
            return 1.35;
        }
        return 1.60;
    }

    private String happinessState(int happiness) {
        if (happiness <= -5) {
            return "Бунт";
        }
        if (happiness <= -3) {
            return "Недовольство";
        }
        if (happiness <= -1) {
            return "Несчастье";
        }
        if (happiness == 0) {
            return "Обычное состояние";
        }
        if (happiness <= 2) {
            return "Счастливые";
        }
        if (happiness <= 4) {
            return "Ликование";
        }
        return "Экстаз";
    }

    private record TownBaseProduction(long id, double baseHammersPerHour, double baseBeakersPerHour, double baseMoneyPerHour, int baseHappiness) {
    }

    private record TownProduction(double hammersPerHour, double beakersPerHour, double moneyPerHour, int happiness, String state, double multiplier) {
    }

    public int townCount(long civId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) AS count FROM towns WHERE civ_id = ?")) {
            statement.setLong(1, civId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt("count") : 0;
            }
        }
    }

    public void chargeAndStartResearch(UUID uuid, double amount, long civId, String techId) throws SQLException {
        connection.setAutoCommit(false);
        try {
            withdrawResident(uuid, amount);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO civ_research(civ_id, tech_id, progress, updated_at)
                    VALUES(?, ?, 0, ?)
                    ON CONFLICT(civ_id) DO UPDATE SET tech_id = excluded.tech_id, progress = 0, updated_at = excluded.updated_at
                    """)) {
                statement.setLong(1, civId);
                statement.setString(2, techId);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public void chargeResident(UUID uuid, double amount) throws SQLException {
        connection.setAutoCommit(false);
        try {
            withdrawResident(uuid, amount);
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public void depositResident(UUID uuid, double amount) throws SQLException {
        long money = moneyToLong(amount);
        try (PreparedStatement statement = connection.prepareStatement("UPDATE residents SET coins = coins + ?, updated_at = ? WHERE uuid = ?")) {
            statement.setLong(1, money);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, uuid.toString());
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Resident does not exist: " + uuid);
            }
        }
    }

    private void updateResidentName(UUID uuid, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE residents SET name = ?, updated_at = ? WHERE uuid = ?")) {
            statement.setString(1, name);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, uuid.toString());
            statement.executeUpdate();
        }
    }

    private long moneyToLong(double amount) throws SQLException {
        validateMoney(amount);
        return Math.round(amount);
    }

    private void validateMoney(double amount) throws SQLException {
        if (amount < 0 || Double.isNaN(amount) || Double.isInfinite(amount) || amount != Math.rint(amount)) {
            throw new SQLException("Money amount must be a non-negative whole number: " + amount);
        }
    }

    private void withdrawResident(UUID uuid, double amount) throws SQLException {
        long money = moneyToLong(amount);
        try (PreparedStatement check = connection.prepareStatement("SELECT coins FROM residents WHERE uuid = ?")) {
            check.setString(1, uuid.toString());
            try (ResultSet rs = check.executeQuery()) {
                if (!rs.next() || rs.getLong("coins") < money) {
                    throw new SQLException("Not enough coins");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("UPDATE residents SET coins = coins - ?, updated_at = ? WHERE uuid = ?")) {
            statement.setLong(1, money);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, uuid.toString());
            statement.executeUpdate();
        }
    }


    public void deleteCamp(long campId, UUID owner) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE residents SET camp_id = NULL, updated_at = ? WHERE uuid = ? AND camp_id = ?")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, owner.toString());
                statement.setLong(3, campId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM camps WHERE id = ? AND owner_uuid = ?")) {
                statement.setLong(1, campId);
                statement.setString(2, owner.toString());
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public void deleteTown(long townId, UUID mayor) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM structure_chunks WHERE town_id = ?")) {
                statement.setLong(1, townId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM protected_blocks WHERE build_id IN (SELECT id FROM structure_builds WHERE town_id = ?)")) {
                statement.setLong(1, townId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM structure_builds WHERE town_id = ?")) {
                statement.setLong(1, townId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM town_claims WHERE town_id = ?")) {
                statement.setLong(1, townId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("UPDATE residents SET town_id = NULL, updated_at = ? WHERE uuid = ? AND town_id = ?")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, mayor.toString());
                statement.setLong(3, townId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM towns WHERE id = ? AND mayor_uuid = ?")) {
                statement.setLong(1, townId);
                statement.setString(2, mayor.toString());
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public void deleteCivilization(long civId, UUID leader) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement townSelect = connection.prepareStatement("SELECT id FROM towns WHERE civ_id = ?")) {
                townSelect.setLong(1, civId);
                try (ResultSet rs = townSelect.executeQuery()) {
                    while (rs.next()) {
                        long townId = rs.getLong("id");
                        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM structure_chunks WHERE town_id = ?")) {
                            statement.setLong(1, townId);
                            statement.executeUpdate();
                        }
                        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM protected_blocks WHERE build_id IN (SELECT id FROM structure_builds WHERE town_id = ?)")) {
                            statement.setLong(1, townId);
                            statement.executeUpdate();
                        }
                        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM structure_builds WHERE town_id = ?")) {
                            statement.setLong(1, townId);
                            statement.executeUpdate();
                        }
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM town_claims WHERE civ_id = ?")) {
                statement.setLong(1, civId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("UPDATE residents SET town_id = NULL, updated_at = ? WHERE civ_id = ?")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setLong(2, civId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM towns WHERE civ_id = ?")) {
                statement.setLong(1, civId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM civ_research WHERE civ_id = ?")) {
                statement.setLong(1, civId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM civ_technologies WHERE civ_id = ?")) {
                statement.setLong(1, civId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("UPDATE residents SET civ_id = NULL, updated_at = ? WHERE uuid = ? AND civ_id = ?")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, leader.toString());
                statement.setLong(3, civId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM civilizations WHERE id = ? AND leader_uuid = ?")) {
                statement.setLong(1, civId);
                statement.setString(2, leader.toString());
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public void deleteStructureBuild(long buildId) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM structure_chunks WHERE build_id = ?")) {
                statement.setLong(1, buildId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM protected_blocks WHERE build_id = ?")) {
                statement.setLong(1, buildId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM structure_builds WHERE id = ?")) {
                statement.setLong(1, buildId);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private long generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("No generated id returned");
            }
            return keys.getLong(1);
        }
    }

    private ResidentProfile mapResident(ResultSet rs) throws SQLException {
        return new ResidentProfile(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("name"),
                rs.getLong("coins"),
                nullableLong(rs, "civ_id"),
                nullableLong(rs, "town_id"),
                nullableLong(rs, "camp_id")
        );
    }

    private CampRecord mapCamp(ResultSet rs) throws SQLException {
        return new CampRecord(
                rs.getLong("id"),
                rs.getString("name"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                rs.getInt("hitpoints"),
                rs.getInt("firepoints_hours")
        );
    }

    private CivRecord mapCiv(ResultSet rs) throws SQLException {
        return new CivRecord(rs.getLong("id"), rs.getString("name"), rs.getString("tag"), UUID.fromString(rs.getString("leader_uuid")), rs.getLong("coins"), rs.getString("government"));
    }

    private TownClaimRecord mapTownClaim(ResultSet rs) throws SQLException {
        return new TownClaimRecord(
                rs.getString("world"),
                rs.getInt("chunk_x"),
                rs.getInt("chunk_z"),
                rs.getLong("town_id"),
                rs.getLong("civ_id"),
                UUID.fromString(rs.getString("claimed_by_uuid")),
                rs.getLong("claimed_at")
        );
    }

    private TownRecord mapTown(ResultSet rs) throws SQLException {
        long townId = rs.getLong("id");
        TownProduction production = townProduction(townId, rs.getDouble("hammers_per_hour"), rs.getDouble("beakers_per_hour"), rs.getDouble("money_per_hour"), rs.getInt("base_happiness"));
        return new TownRecord(
                townId,
                rs.getString("name"),
                rs.getLong("civ_id"),
                UUID.fromString(rs.getString("mayor_uuid")),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                rs.getInt("level"),
                rs.getLong("coins"),
                production.hammersPerHour(),
                production.beakersPerHour(),
                production.moneyPerHour(),
                production.happiness(),
                production.state(),
                production.multiplier()
        );
    }

    private StructureBuildView mapStructureBuildView(ResultSet rs, Integer infoChunkX, Integer infoChunkZ) throws SQLException {
        Long townId = nullableLong(rs, "town_id");
        String structureId = rs.getString("structure_id");
        String displayName = rs.getString("display_name");
        if (displayName == null || displayName.isBlank()) {
            displayName = structureId == null || structureId.isBlank() ? rs.getString("template") : structureId;
        }
        int chunkX = infoChunkX == null ? Math.floorDiv(rs.getInt("x"), 16) : infoChunkX;
        int chunkZ = infoChunkZ == null ? Math.floorDiv(rs.getInt("z"), 16) : infoChunkZ;
        return new StructureBuildView(
                rs.getLong("id"),
                townId,
                rs.getString("town_name"),
                structureId,
                displayName,
                rs.getString("status"),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                chunkX,
                chunkZ,
                rs.getLong("cost"),
                rs.getDouble("total_hammers"),
                rs.getDouble("hammers_per_hour"),
                rs.getLong("started_at"),
                rs.getLong("finish_at"),
                rs.getLong("created_at")
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public boolean isFirstCompletedStructureInTown(long buildId, long townId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id
                FROM structure_builds
                WHERE town_id = ? AND status = 'COMPLETE'
                ORDER BY created_at ASC, id ASC
                LIMIT 1
                """)) {
            statement.setLong(1, townId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getLong("id") == buildId;
            }
        }
    }

    @Override
    public void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to close CivCraft storage cleanly: " + exception.getMessage());
        }
    }
}
