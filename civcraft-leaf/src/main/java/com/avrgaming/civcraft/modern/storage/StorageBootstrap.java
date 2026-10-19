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
    private com.avrgaming.civcraft.modern.culture.BiomePotential cultureBiomes;
    private Double legacyTownBaseHammers;
    private com.avrgaming.civcraft.modern.town.HappinessRules happinessRules;
    private com.avrgaming.civcraft.modern.town.ConsumptionCatalog consumptionCatalog;
    private com.avrgaming.civcraft.modern.wonder.WonderBonuses wonderBonuses;
    private com.avrgaming.civcraft.modern.trade.GoodieEffects goodieEffects;
    private com.avrgaming.civcraft.modern.build.HammerCosts hammerCosts;
    public synchronized void setHammerCosts(com.avrgaming.civcraft.modern.build.HammerCosts costs){hammerCosts=costs;}
    public synchronized double buildHammerCost(long town,String type,double base)throws SQLException {return hammerCosts==null?base:hammerCosts.cost(connection,town,type,base,townGoodieEffects(town).value("buff_rush"));}
    public synchronized void setGoodieEffects(com.avrgaming.civcraft.modern.trade.GoodieEffects effects){goodieEffects=effects;}
    public synchronized int civilizationMedicine(long civilization)throws SQLException {return goodieEffects==null?0:goodieEffects.medicine(connection,civilization);}
    public synchronized com.avrgaming.civcraft.modern.trade.GoodieEffects.Values townGoodieEffects(long town)throws SQLException {return goodieEffects==null?com.avrgaming.civcraft.modern.trade.GoodieEffects.Values.NONE:goodieEffects.town(connection,town);}
    public synchronized void setWonderBonuses(com.avrgaming.civcraft.modern.wonder.WonderBonuses bonuses){wonderBonuses=bonuses;}
    public synchronized com.avrgaming.civcraft.modern.wonder.WonderBonuses.Values townWonderBonuses(long town)throws SQLException {return wonderBonuses==null?com.avrgaming.civcraft.modern.wonder.WonderBonuses.Values.NONE:wonderBonuses.town(connection,town);}
    public synchronized void setConsumptionCatalog(com.avrgaming.civcraft.modern.town.ConsumptionCatalog catalog){consumptionCatalog=catalog;}
    public synchronized void setHappinessRules(com.avrgaming.civcraft.modern.town.HappinessRules rules){happinessRules=rules;}
    public synchronized void setLegacyTownBaseHammers(double rate){legacyTownBaseHammers=rate;}
    public synchronized double townBaseHammers(double fallback){return legacyTownBaseHammers==null?fallback:legacyTownBaseHammers;}
    public synchronized double staticTownCulture(long town)throws SQLException {
        double total=0;
        var goodies=townGoodieEffects(town);
        try(var s=connection.prepareStatement("SELECT structure_id,COUNT(*) FROM active_structure_builds WHERE town_id=? AND status='COMPLETE' GROUP BY structure_id")) {
            s.setLong(1,town);try(var r=s.executeQuery()){while(r.next())if(structureDefinitions!=null)total+=(structureDefinitions.attributes(r.getString(1)).get("CULTURE")+structureDefinitions.tradeAttributes(r.getString(1)).calculate(goodies.goods()).get("CULTURE"))*r.getInt(2);}
        }
        return total+(wonderBonuses==null?0:wonderBonuses.globeCulture(connection,town));
    }
    public synchronized void setCultureBiomes(com.avrgaming.civcraft.modern.culture.BiomePotential catalog){cultureBiomes=catalog;}
    private long joinCooldownMillis=12L*3600000;
    private double capturedPenalty=.5;
    public synchronized void setCapturedPenalty(double value){com.avrgaming.civcraft.modern.war.OccupationRules.validatePenalty(value);capturedPenalty=value;}
    public synchronized double capturedPenalty(){return capturedPenalty;}
    public synchronized void setJoinCooldownMillis(long duration){joinCooldownMillis=duration;}
    private final JavaPlugin plugin;
    private final ModernCivCraftSettings settings;
    private Connection connection;
    private com.avrgaming.civcraft.modern.government.GovernmentDefinitions governments;
    private com.avrgaming.civcraft.modern.build.StructureDefinitionService structureDefinitions;
    public void setStructureDefinitions(com.avrgaming.civcraft.modern.build.StructureDefinitionService definitions){this.structureDefinitions=definitions;}

    public void setGovernments(com.avrgaming.civcraft.modern.government.GovernmentDefinitions governments) {
        this.governments=governments;
    }

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

    public synchronized void initializeLocalStorage() {
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
            com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.suspendForBootstrap(connection);
            createBootstrapSchema();
            migrateBootstrapSchema();
            com.avrgaming.civcraft.modern.build.BuildingActivityStore.install(connection);
            WonderUniqueness.install(connection);
            com.avrgaming.civcraft.modern.trade.TradeStore.install(connection);
            com.avrgaming.civcraft.modern.build.StructureChestStore.install(connection);
            normalizeMoneyValues();
            plugin.getLogger().info("Local CivCraft SQLite storage ready: " + databaseFile);
        } catch (IOException | SQLException exception) {
            plugin.getLogger().severe("Unable to initialize local CivCraft storage: " + exception.getMessage());
            close();
            connection = null;
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    /** Shares the connection lock with all existing gameplay/storage operations. */
    public synchronized <T> T query(SqlWork<T> work) throws SQLException {
        if (!isReady()) throw new SQLException("CivCraft storage is not initialized");
        return work.apply(connection);
    }

    public synchronized <T> T transaction(SqlWork<T> work) throws SQLException {
        if (!isReady()) throw new SQLException("CivCraft storage is not initialized");
        if (!connection.getAutoCommit()) throw new SQLException("Nested CivCraft transaction is not supported");
        connection.setAutoCommit(false);
        try {
            T result = work.apply(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void applySqlitePragmas(Connection target) throws SQLException {
        try (Statement statement = target.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA recursive_triggers = ON");
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
                        income_remainder REAL NOT NULL DEFAULT 0,
                        base_happiness INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(civ_id) REFERENCES civilizations(id) ON DELETE CASCADE,
                        FOREIGN KEY(mayor_uuid) REFERENCES residents(uuid) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS settlement_invites (
                        target_uuid TEXT NOT NULL,
                        kind TEXT NOT NULL CHECK(kind IN ('CIV', 'TOWN')),
                        settlement_id INTEGER NOT NULL,
                        inviter_uuid TEXT NOT NULL,
                        expires_at INTEGER NOT NULL,
                        PRIMARY KEY(target_uuid, kind)
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_settlement_invites_expiry ON settlement_invites(expires_at)");
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
                        snapshot_ready INTEGER NOT NULL DEFAULT 0,
                        journal_started INTEGER NOT NULL DEFAULT 0,
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
                        block_nbt TEXT,
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
        boolean conquestAuto=connection.getAutoCommit();
        if(conquestAuto)connection.setAutoCommit(false);
        try{com.avrgaming.civcraft.modern.war.ConquestStore.install(connection);if(conquestAuto)connection.commit();}
        catch(SQLException|RuntimeException failure){if(conquestAuto)connection.rollback();throw failure;}
        finally{if(conquestAuto)connection.setAutoCommit(true);}
        addColumnIfMissing("civilizations", "tag", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing("towns", "hammers_per_hour", "REAL NOT NULL DEFAULT 100.0");
        addColumnIfMissing("towns", "beakers_per_hour", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing("towns", "money_per_hour", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing("towns", "income_remainder", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing("towns", "base_happiness", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("structure_builds", "town_id", "INTEGER");
        addColumnIfMissing("structure_builds", "total_hammers", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing("structure_builds", "base_hammers", "REAL");
        try(var statement=connection.createStatement()){statement.executeUpdate("UPDATE structure_builds SET base_hammers=total_hammers WHERE base_hammers IS NULL");}
        addColumnIfMissing("structure_builds", "hammers_per_hour", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing("structure_builds", "production_hammers_per_hour", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing("structure_builds", "beakers_per_hour", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing("structure_builds", "money_per_hour", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing("structure_builds", "happiness", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("structure_builds", "started_at", "INTEGER");
        addColumnIfMissing("structure_builds", "finish_at", "INTEGER");
        addColumnIfMissing("structure_builds", "snapshot_ready", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("structure_builds", "journal_started", "INTEGER NOT NULL DEFAULT 0");
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
                        block_nbt TEXT,
                        PRIMARY KEY(build_id, world, x, y, z),
                        FOREIGN KEY(build_id) REFERENCES structure_builds(id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_structure_chunks_build ON structure_chunks(build_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_structure_builds_town ON structure_builds(town_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_structure_block_backups_build ON structure_block_backups(build_id)");
        }
        addColumnIfMissing("structure_block_backups", "block_nbt", "TEXT");
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

    public synchronized void warmupAsync() {
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

    public synchronized boolean isReady() {
        return connection != null;
    }

    public synchronized ResidentProfile getOrCreateResident(UUID uuid, String name, double startingCoins) throws SQLException {
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

    public synchronized Optional<ResidentProfile> findResident(UUID uuid) throws SQLException {
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

    public synchronized Optional<ResidentProfile> findResidentByName(String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM residents WHERE name = ? COLLATE NOCASE LIMIT 1")) {
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapResident(rs)) : Optional.empty();
            }
        }
    }

    public synchronized Optional<CampRecord> findCampByOwner(UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM camps WHERE owner_uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapCamp(rs)) : Optional.empty();
            }
        }
    }


    /**
     * Persistent, single-use invitation. One outstanding invitation per player
     * and kind; newer invitations replace older ones. Caller checks roles.
     */
    public synchronized void issueSettlementInvite(UUID target, String kind, long settlementId, UUID inviter, long expiresAt) throws SQLException {
        if (!kind.equals("CIV") && !kind.equals("TOWN")) {
            throw new IllegalArgumentException("Неверный тип приглашения в поселение");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO settlement_invites(target_uuid, kind, settlement_id, inviter_uuid, expires_at)
                VALUES(?, ?, ?, ?, ?)
                ON CONFLICT(target_uuid, kind) DO UPDATE SET
                  settlement_id=excluded.settlement_id,
                  inviter_uuid=excluded.inviter_uuid,
                  expires_at=excluded.expires_at
                """)) {
            statement.setString(1, target.toString());
            statement.setString(2, kind);
            statement.setLong(3, settlementId);
            statement.setString(4, inviter.toString());
            statement.setLong(5, expiresAt);
            statement.executeUpdate();
        }
    }

    public synchronized String acceptSettlementInvite(UUID target, String kind) throws SQLException {
        if (!kind.equals("CIV") && !kind.equals("TOWN")) {
            throw new IllegalArgumentException("Неверный тип приглашения");
        }
        connection.setAutoCommit(false);
        try {
            long now = System.currentTimeMillis();
            if(kind.equals("TOWN"))com.avrgaming.civcraft.modern.town.TaxStore.validateRejoin(connection,target,now,joinCooldownMillis);
            long settlementId;
            String inviter;
            try (PreparedStatement find = connection.prepareStatement("""
                    SELECT settlement_id, inviter_uuid FROM settlement_invites
                    WHERE target_uuid = ? AND kind = ? AND expires_at > ?
                    """)) {
                find.setString(1, target.toString());
                find.setString(2, kind);
                find.setLong(3, now);
                try (ResultSet rs = find.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Приглашение отсутствует или истекло (24 часа).");
                    }
                    settlementId = rs.getLong("settlement_id");
                    inviter = rs.getString("inviter_uuid");
                }
            }
            String name;
            long civId;
            if (kind.equals("CIV")) {
                try (PreparedStatement find = connection.prepareStatement(
                        "SELECT name, leader_uuid FROM civilizations WHERE id = ?")) {
                    find.setLong(1, settlementId);
                    try (ResultSet rs = find.executeQuery()) {
                        if (!rs.next() || !rs.getString("leader_uuid").equals(inviter)) {
                            throw new IllegalArgumentException("Приглашение больше не действительно: лидер сменился.");
                        }
                        name = rs.getString("name");
                    }
                }
                civId = settlementId;
            } else {
                try (PreparedStatement find = connection.prepareStatement(
                        "SELECT name, civ_id, mayor_uuid FROM towns WHERE id = ?")) {
                    find.setLong(1, settlementId);
                    try (ResultSet rs = find.executeQuery()) {
                        if (!rs.next() || !rs.getString("mayor_uuid").equals(inviter)) {
                            throw new IllegalArgumentException("Приглашение больше не действительно: мэр сменился.");
                        }
                        name = rs.getString("name");
                        civId = rs.getLong("civ_id");
                    }
                }
            }
            String sql = kind.equals("CIV")
                    ? "UPDATE residents SET civ_id=?, updated_at=? WHERE uuid=? AND civ_id IS NULL AND camp_id IS NULL"
                    : "UPDATE residents SET civ_id=?, town_id=?, updated_at=? WHERE uuid=? "
                      + "AND town_id IS NULL AND camp_id IS NULL AND (civ_id IS NULL OR civ_id=?)";
            try (PreparedStatement update = connection.prepareStatement(sql)) {
                update.setLong(1, civId);
                int idx = 2;
                if (kind.equals("TOWN")) {
                    update.setLong(idx++, settlementId);
                }
                update.setLong(idx++, now);
                update.setString(idx++, target.toString());
                if (kind.equals("TOWN")) {
                    update.setLong(idx, civId);
                }
                if (update.executeUpdate() != 1) {
                    throw new IllegalArgumentException("Сначала покиньте прежнее поселение или лагерь.");
                }
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM settlement_invites WHERE target_uuid = ? AND kind = ?")) {
                delete.setString(1, target.toString());
                delete.setString(2, kind);
                delete.executeUpdate();
            }
            // Accepting a town invitation makes a separate CIV invitation obsolete.
            if (kind.equals("TOWN")) {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM settlement_invites WHERE target_uuid = ? AND kind = 'CIV'")) {
                    delete.setString(1, target.toString());
                    delete.executeUpdate();
                }
            }
            connection.commit();
            return name;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized void transferCivilizationLeadership(long civId, UUID currentLeader, UUID newLeader) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE civilizations SET leader_uuid = ?
                WHERE id = ? AND leader_uuid = ?
                AND EXISTS(SELECT 1 FROM residents WHERE uuid = ? AND civ_id = ?)
                """)) {
            statement.setString(1, newLeader.toString());
            statement.setLong(2, civId);
            statement.setString(3, currentLeader.toString());
            statement.setString(4, newLeader.toString());
            statement.setLong(5, civId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("Передача невозможна: новый лидер не состоит в цивилизации.");
            }
        }
    }

    public synchronized void transferTownMayorship(long townId, UUID currentMayor, UUID newMayor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE towns SET mayor_uuid = ?
                WHERE id = ? AND mayor_uuid = ?
                AND EXISTS(SELECT 1 FROM residents WHERE uuid = ? AND town_id = ?)
                """)) {
            statement.setString(1, newMayor.toString());
            statement.setLong(2, townId);
            statement.setString(3, currentMayor.toString());
            statement.setString(4, newMayor.toString());
            statement.setLong(5, townId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("Передача невозможна: новый мэр не состоит в городе.");
            }
        }
    }

    public synchronized List<String> settlementMemberNames(String kind, long id) throws SQLException {
        if (!kind.equals("CIV") && !kind.equals("TOWN")) {
            throw new IllegalArgumentException("Неверный тип поселения");
        }
        List<String> names = new ArrayList<>();
        String sql = kind.equals("CIV")
                ? "SELECT name FROM residents WHERE civ_id = ? ORDER BY name COLLATE NOCASE"
                : "SELECT name FROM residents WHERE town_id = ? ORDER BY name COLLATE NOCASE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    /**
     * This is a membership change, not settlement destruction. Founders and
     * mayors are protected by an ownership predicate inside the UPDATE.
     */
    public synchronized void removeSettlementMember(UUID player, String kind, long settlementId) throws SQLException {
        if (!kind.equals("CIV") && !kind.equals("TOWN")) {
            throw new IllegalArgumentException("Неверный тип поселения");
        }
        connection.setAutoCommit(false);
        try {
            String sql = kind.equals("TOWN")
                    ? """
                      UPDATE residents SET town_id=NULL, updated_at=? WHERE uuid=? AND town_id=?
                      AND NOT EXISTS(SELECT 1 FROM towns WHERE id=? AND mayor_uuid=?)
                      """
                    : """
                      UPDATE residents SET town_id=NULL, civ_id=NULL, updated_at=? WHERE uuid=? AND civ_id=?
                      AND NOT EXISTS(SELECT 1 FROM civilizations WHERE id=? AND leader_uuid=?)
                      AND NOT EXISTS(SELECT 1 FROM towns WHERE civ_id=? AND mayor_uuid=?)
                      """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, player.toString());
                statement.setLong(3, settlementId);
                statement.setLong(4, settlementId);
                statement.setString(5, player.toString());
                if (kind.equals("CIV")) {
                    statement.setLong(6, settlementId);
                    statement.setString(7, player.toString());
                }
                if (statement.executeUpdate() != 1) {
                    throw new IllegalArgumentException(
                            "Игрок не состоит в поселении, либо является лидером/мэром. Передача прав пока недоступна.");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM settlement_invites WHERE target_uuid = ?")) {
                statement.setString(1, player.toString());
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized Optional<CivRecord> findCiv(long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM civilizations WHERE id = ? AND NOT EXISTS(SELECT 1 FROM civ_conquests WHERE civ_id=civilizations.id)")) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapCiv(rs)) : Optional.empty();
            }
        }
    }

    public synchronized Optional<TownRecord> findTown(long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM towns WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapTown(rs)) : Optional.empty();
            }
        }
    }

    public synchronized void depositTown(long townId, double amount) throws SQLException {
        long money = moneyToLong(amount);
        try (PreparedStatement statement = connection.prepareStatement("UPDATE towns SET coins = coins + ? WHERE id = ?")) {
            statement.setLong(1, money);
            statement.setLong(2, townId);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Town does not exist: " + townId);
            }
        }
    }

    public synchronized void withdrawTown(long townId, double amount) throws SQLException {
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

    public synchronized void depositCiv(long civId, double amount) throws SQLException {
        long money = moneyToLong(amount);
        try (PreparedStatement statement = connection.prepareStatement("UPDATE civilizations SET coins = coins + ? WHERE id = ?")) {
            statement.setLong(1, money);
            statement.setLong(2, civId);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Civilization does not exist: " + civId);
            }
        }
    }

    public synchronized void withdrawCiv(long civId, double amount) throws SQLException {
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

    public synchronized boolean hasTownNear(Location location, double distance) throws SQLException {
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

    public synchronized CampRecord createCamp(UUID owner, String name, Location location, double cost, int hitpoints, int firepointsHours) throws SQLException {
        connection.setAutoCommit(false);
        try {
            com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.register(connection,owner);
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
            com.avrgaming.civcraft.modern.foundation.FoundationCreationStore.captureCamp(connection,campId,owner,name,location.getWorld().getName(),location.getBlockX(),location.getBlockY(),location.getBlockZ());
            com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.registered(connection,owner);
            connection.commit();
            return findCampByOwner(owner).orElseThrow();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized CivRecord createCivilization(UUID leader, String name, double cost, String government) throws SQLException {
        return createCivilization(leader, name, "", cost, government);
    }

    public synchronized CivRecord createCivilization(UUID leader, String name, String tag, double cost, String government) throws SQLException {
        connection.setAutoCommit(false);
        try {
            com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.register(connection,leader);
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
            com.avrgaming.civcraft.modern.foundation.FoundationCreationStore.captureCivilization(connection,civId,leader,name,tag);
            com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.registered(connection,leader);
            connection.commit();
            return findCiv(civId).orElseThrow();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized TownRecord createTown(UUID mayor, long civId, String name, Location location, double cost, double hammersPerHour) throws SQLException {
        connection.setAutoCommit(false);
        try {
            com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.register(connection,mayor);
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
            com.avrgaming.civcraft.modern.foundation.FoundationCreationStore.captureTown(connection,townId,mayor,civId,name,location.getWorld().getName(),location.getBlockX(),location.getBlockY(),location.getBlockZ());
            com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.registered(connection,mayor);
            connection.commit();
            return findTown(townId).orElseThrow();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized Optional<TownClaimRecord> findTownClaim(String world, int chunkX, int chunkZ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM town_claims WHERE world = ? AND chunk_x = ? AND chunk_z = ?")) {
            statement.setString(1, world);
            statement.setInt(2, chunkX);
            statement.setInt(3, chunkZ);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapTownClaim(rs)) : Optional.empty();
            }
        }
    }

    public synchronized int townClaimCount(long townId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) AS count FROM town_claims WHERE town_id = ?")) {
            statement.setLong(1, townId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt("count") : 0;
            }
        }
    }

    public synchronized TownClaimRecord claimTownChunk(UUID claimant, long townId, long civId, String world, int chunkX, int chunkZ, double cost) throws SQLException {
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

    public synchronized void unclaimTownChunk(long townId, String world, int chunkX, int chunkZ) throws SQLException {
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

    public synchronized long recordPreparedStructureBuild(UUID builder, Long townId, String type, String template, String direction, Location origin, String templatePath, int queuedBlocks, String structureId, String displayName, double cost, int maxHitpoints, double totalHammers, double hammersPerHour, double productionHammersPerHour, double beakersPerHour, double moneyPerHour, int happiness, long startedAt, long finishAt, Collection<StructureChunkCoord> chunks) throws SQLException {
        return transaction(db -> {
            com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.register(db,builder);
            try {
            long id=recordStructureBuild(builder,townId,type,template,direction,origin,templatePath,queuedBlocks,structureId,displayName,cost,maxHitpoints,totalHammers,hammersPerHour,productionHammersPerHour,beakersPerHour,moneyPerHour,happiness,startedAt,finishAt);
            recordStructureChunks(id,townId,chunks);
            com.avrgaming.civcraft.modern.foundation.FoundationCreationStore.buildReady(db,id);
            return id;
            }finally{com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.registered(db,builder);}
        });
    }

    public synchronized long recordStructureBuild(UUID builder, Long townId, String type, String template, String direction, Location origin, String templatePath, int queuedBlocks, String structureId, String displayName, double cost, int maxHitpoints, double totalHammers, double hammersPerHour, double productionHammersPerHour, double beakersPerHour, double moneyPerHour, int happiness, long startedAt, long finishAt) throws SQLException {
        double baseHammers=totalHammers;
        if(townId!=null)totalHammers=buildHammerCost(townId,structureId,baseHammers);
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO structure_builds(builder_uuid, town_id, type, template, direction, world, x, y, z, template_path, queued_blocks, structure_id, display_name, cost, max_hitpoints, total_hammers, hammers_per_hour, production_hammers_per_hour, beakers_per_hour, money_per_hour, happiness, started_at, finish_at, status, journal_started, created_at,base_hammers)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', 1, ?, ?)
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
            statement.setDouble(25,baseHammers);
            statement.executeUpdate();
            long buildId=generatedId(statement);
            com.avrgaming.civcraft.modern.foundation.FoundationCreationStore.captureBuild(connection,buildId,builder,townId,structureId,direction,origin.getWorld().getName(),origin.getBlockX(),origin.getBlockY(),origin.getBlockZ());
            com.avrgaming.civcraft.modern.war.TowerHealthStore.initialize(connection,buildId);
            return buildId;
        }
    }

    /** Keep buildings in progress or recovery locked until their terrain is stable. */
    public synchronized boolean isActiveStructureChunk(String world, int cx, int cz) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM structure_chunks sc
                JOIN structure_builds sb ON sb.id=sc.build_id
                WHERE sc.world=? AND sc.chunk_x=? AND sc.chunk_z=?
                AND sb.status IN ('QUEUED','BUILDING','ROLLBACK_PENDING','DEMOLISH_PENDING','CAMP_RESTORE_PENDING')
                LIMIT 1
                """)) {
            statement.setString(1, world);
            statement.setInt(2, cx);
            statement.setInt(3, cz);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    public synchronized boolean hasStructureChunkOverlap(Collection<StructureChunkCoord> chunks) throws SQLException {
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

    public synchronized int countTownStructureBuildings(long townId, String structureId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM structure_builds
                WHERE town_id = ? AND structure_id = ? COLLATE NOCASE
                AND status NOT IN ('CANCELLED', 'DEMOLISHED')
                """)) {
            statement.setLong(1, townId);
            statement.setString(2, structureId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public synchronized boolean isWonderReserved(String structureId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM structure_builds
                WHERE lower(structure_id) = lower(?)
                AND lower(structure_id) NOT IN ('w_colosseum','w_battledome')
                AND status IN ('COMPLETE', 'DEMOLISH_PENDING') LIMIT 1
                """)) {
            statement.setString(1, structureId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    public synchronized boolean hasActiveStructureBuild(long townId) throws SQLException {
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

    public synchronized Optional<StructureBuildView> findActiveStructureBuildForTown(long townId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT sb.*, t.name AS town_name
                FROM structure_builds sb
                LEFT JOIN towns t ON t.id = sb.town_id
                WHERE sb.town_id = ? AND sb.status IN ('QUEUED', 'BUILDING')
                ORDER BY sb.created_at DESC
                LIMIT 1
                """)) {
            statement.setLong(1, townId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapStructureBuildView(rs, null, null)) : Optional.empty();
            }
        }
    }

    public synchronized Optional<StructureBuildView> findStructureBuildAtChunk(String world, int chunkX, int chunkZ) throws SQLException {
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

    /**
     * Atomic idempotency boundary: take one refund only when transitioning from
     * QUEUED to ROLLBACK_PENDING. Keep claims locked and original snapshots
     * intact until world blocks have been restored.
     */
    public synchronized void requestStructureRollback(long buildId, long townId, long refund) throws SQLException {
        if (refund < 0L) {
            throw new SQLException("Refund must be non-negative");
        }
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE structure_builds
                    SET status = 'ROLLBACK_PENDING'
                    WHERE id = ? AND town_id = ? AND status = 'QUEUED'
                    """)) {
                statement.setLong(1, buildId);
                statement.setLong(2, townId);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalArgumentException("Стройка уже отменена или завершена.");
                }
            }
            if (refund > 0L) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE towns SET coins = coins + ? WHERE id = ?")) {
                    update.setLong(1, refund);
                    update.setLong(2, townId);
                    if (update.executeUpdate() != 1) {
                        throw new SQLException("Town not found while refunding cancelled structure #" + buildId);
                    }
                }
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** Mark a rollback complete only after terrain restoration has finished. */
    public synchronized void finishStructureRollback(long buildId) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE structure_builds
                    SET status = 'CANCELLED', completed_at = ?
                    WHERE id = ? AND status = 'ROLLBACK_PENDING'
                    """)) {
                update.setLong(1, System.currentTimeMillis());
                update.setLong(2, buildId);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Structure rollback is not pending: " + buildId);
                }
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM protected_blocks WHERE build_id = ?")) {
                delete.setLong(1, buildId);
                delete.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM structure_chunks WHERE build_id = ?")) {
                delete.setLong(1, buildId);
                delete.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized void cancelStructureBuild(long buildId) throws SQLException {
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

    public synchronized void recordStructureChunks(long buildId, Long townId, Collection<StructureChunkCoord> chunks) throws SQLException {
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

    public synchronized void completeStructureBuild(long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE structure_builds SET status = 'COMPLETE', completed_at = ? WHERE id = ? AND status IN ('QUEUED', 'BUILDING')")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setLong(2, id);
            statement.executeUpdate();
        }
    }

    public synchronized void recordProtectedBlocks(long buildId, Collection<BlockPlacement> placements) throws SQLException {
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

    public synchronized void recordProtectedBlocksAndCompleteBuild(long buildId, Collection<BlockPlacement> placements) throws SQLException {
        Path databaseFile = settings.sqlitePath(plugin.getDataFolder().toPath());
        try (Connection writeConnection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            applySqlitePragmas(writeConnection);
            writeConnection.setAutoCommit(false);
            try (
                    PreparedStatement insert = writeConnection.prepareStatement("""
                            INSERT OR REPLACE INTO protected_blocks(world, x, y, z, build_id, material)
                            VALUES(?, ?, ?, ?, ?, ?)
                            """);
                    PreparedStatement complete = writeConnection.prepareStatement("UPDATE structure_builds SET status = 'COMPLETE', completed_at = ? WHERE id = ? AND status IN ('QUEUED', 'BUILDING')")
            ) {
                // Claim completion at transaction start. If a cancellation
                // already won, never resurrect protected blocks or status.
                complete.setLong(1, System.currentTimeMillis());
                complete.setLong(2, buildId);
                if (complete.executeUpdate() != 1) {
                    writeConnection.rollback();
                    return;
                }
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
                com.avrgaming.civcraft.modern.build.StructureChestStore.record(writeConnection,buildId,placements);
                writeConnection.commit();
            } catch (SQLException exception) {
                writeConnection.rollback();
                throw exception;
            } finally {
                writeConnection.setAutoCommit(true);
            }
        }
    }

    public synchronized boolean recordProtectedBlocksBackupsAndCompleteBuild(long buildId, Collection<BlockPlacement> placements, Collection<BlockBackup> backups) throws SQLException {
        Path databaseFile = settings.sqlitePath(plugin.getDataFolder().toPath());
        try (Connection writeConnection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            applySqlitePragmas(writeConnection);
            writeConnection.setAutoCommit(false);
            try (
                    PreparedStatement backupInsert = writeConnection.prepareStatement("""
                            INSERT OR IGNORE INTO structure_block_backups(build_id, world, x, y, z, block_data, block_nbt)
                            VALUES(?, ?, ?, ?, ?, ?, ?)
                            """);
                    PreparedStatement insert = writeConnection.prepareStatement("""
                            INSERT OR REPLACE INTO protected_blocks(world, x, y, z, build_id, material)
                            VALUES(?, ?, ?, ?, ?, ?)
                            """);
                    PreparedStatement complete = writeConnection.prepareStatement("UPDATE structure_builds SET status = 'COMPLETE', completed_at = ? WHERE id = ? AND status IN ('QUEUED', 'BUILDING')")
            ) {
                // Claim completion at transaction start. If a cancellation
                // already won, never resurrect protected blocks or status.
                if (WonderUniqueness.rollbackLosingBuild(writeConnection, buildId)) {
                    writeConnection.commit();
                    return false;
                }
                complete.setLong(1, System.currentTimeMillis());
                complete.setLong(2, buildId);
                if (complete.executeUpdate() != 1) {
                    writeConnection.rollback();
                    return true;
                }
                int batchSize = Math.max(1, settings.maxDatabaseWritesPerTick());
                int pendingBackups = 0;
                for (BlockBackup backup : backups) {
                    backupInsert.setLong(1, buildId);
                    backupInsert.setString(2, backup.world());
                    backupInsert.setInt(3, backup.x());
                    backupInsert.setInt(4, backup.y());
                    backupInsert.setInt(5, backup.z());
                    backupInsert.setString(6, backup.blockData());
                    backupInsert.setString(7, backup.blockNbt());
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
                com.avrgaming.civcraft.modern.trade.TradeStore.recordPoints(writeConnection,buildId,placements);
                com.avrgaming.civcraft.modern.build.StructureChestStore.record(writeConnection,buildId,placements);
                writeConnection.commit();
                return true;
            } catch (SQLException exception) {
                writeConnection.rollback();
                throw exception;
            } finally {
                writeConnection.setAutoCommit(true);
            }
        }
    }

    /**
     * Write-ahead terrain journal. Flush and commit each batch *before* the
     * corresponding build session mutates any block. INSERT OR IGNORE retains
     * the original snapshot on a build that was resumed after a restart.
     */
    public synchronized void persistStructureSnapshot(long buildId, List<BlockBackup> originals, boolean complete) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement check = connection.prepareStatement("""
                    SELECT status FROM structure_builds WHERE id = ?
                    """)) {
                check.setLong(1, buildId);
                try (ResultSet rs = check.executeQuery()) {
                    if (!rs.next() || !(rs.getString(1).equalsIgnoreCase("QUEUED") || rs.getString(1).equalsIgnoreCase("BUILDING"))) {
                        throw new SQLException("Cannot snapshot cancelled or missing build #" + buildId);
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT OR IGNORE INTO structure_block_backups(build_id, world, x, y, z, block_data, block_nbt)
                    VALUES(?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (BlockBackup block : originals) {
                    insert.setLong(1, buildId);
                    insert.setString(2, block.world());
                    insert.setInt(3, block.x());
                    insert.setInt(4, block.y());
                    insert.setInt(5, block.z());
                    insert.setString(6, block.blockData());
                    insert.setString(7, block.blockNbt());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            if (complete) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE structure_builds SET snapshot_ready = 1 WHERE id = ? AND status IN ('QUEUED', 'BUILDING')
                        """)) {
                    statement.setLong(1, buildId);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Build was cancelled while snapshotting #" + buildId);
                    }
                }
            }
            try (PreparedStatement mark = connection.prepareStatement(
                    "UPDATE structure_builds SET journal_started = 1 WHERE id = ?")) {
                mark.setLong(1, buildId);
                mark.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public record InterruptedBuild(long id, String status, String world, int x, int y, int z,
                                   String templatePath, long startedAt,long finishAt, boolean journalStarted,
                                   boolean snapshotReady) {
    }

    public synchronized List<InterruptedBuild> interruptedStructureBuilds() throws SQLException {
        List<InterruptedBuild> interrupted = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id, status, world, x, y, z, template_path, started_at,finish_at,
                       journal_started, snapshot_ready
                FROM structure_builds
                WHERE status IN ('QUEUED', 'BUILDING', 'ROLLBACK_PENDING', 'DEMOLISH_PENDING')
                ORDER BY id ASC
                """);
             ResultSet rs = query.executeQuery()) {
            while (rs.next()) {
                interrupted.add(new InterruptedBuild(
                        rs.getLong("id"), rs.getString("status"), rs.getString("world"),
                        rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                        rs.getString("template_path"), rs.getLong("started_at"),rs.getLong("finish_at"),
                        rs.getInt("journal_started") != 0,
                        rs.getInt("snapshot_ready") != 0));
            }
        }
        return interrupted;
    }

    public synchronized List<BlockBackup> loadStructureBlockBackups(long buildId) throws SQLException {
        List<BlockBackup> backups = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT world, x, y, z, block_data, block_nbt
                FROM structure_block_backups
                WHERE build_id = ?
                ORDER BY y DESC
                """)) {
            statement.setLong(1, buildId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    backups.add(new BlockBackup(rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"), rs.getString("block_data"), rs.getString("block_nbt")));
                }
            }
        }
        return backups;
    }

    /** Atomically reserve a finished building for demolition and pay one refund. */
    public synchronized void requestStructureDemolition(long buildId, long townId, long refund) throws SQLException {
        if (refund < 0L) {
            throw new SQLException("Demolition refund must be non-negative");
        }
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE structure_builds SET status = 'DEMOLISH_PENDING'
                    WHERE id = ? AND town_id = ? AND status = 'COMPLETE'
                    """)) {
                update.setLong(1, buildId);
                update.setLong(2, townId);
                if (update.executeUpdate() != 1) {
                    throw new IllegalArgumentException("Здание уже сносится либо не завершено.");
                }
            }
            if (refund > 0L) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE towns SET coins = coins + ? WHERE id = ?")) {
                    update.setLong(1, refund);
                    update.setLong(2, townId);
                    if (update.executeUpdate() != 1) {
                        throw new SQLException("Town missing during demolition #" + buildId);
                    }
                }
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** Delete structure ownership only AFTER world rollback completes. */
    public synchronized void finishStructureDemolition(long buildId) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE structure_builds SET status = 'DEMOLISHED', completed_at = ?
                    WHERE id = ? AND status = 'DEMOLISH_PENDING'
                    """)) {
                update.setLong(1, System.currentTimeMillis());
                update.setLong(2, buildId);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Demolition not pending for #" + buildId);
                }
            }
            for (String table : List.of("protected_blocks", "structure_chunks", "structure_block_backups")) {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE build_id = ?")) {
                    delete.setLong(1, buildId);
                    delete.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized void demolishStructureBuild(long buildId) throws SQLException {
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

    public synchronized boolean isProtectedBlock(String world, int x, int y, int z) throws SQLException {
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

    public synchronized Set<String> researchedTechs(long civId) throws SQLException {
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

    public synchronized Optional<ResearchProgress> researchProgress(long civId, double requiredBeakers) throws SQLException {
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

    public synchronized void startResearch(long civId, String techId) throws SQLException {
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

    public synchronized List<ActiveResearch> activeResearches() throws SQLException {
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

    public synchronized boolean addResearchBeakers(long civId, String techId, double beakers, double requiredBeakers) throws SQLException {
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


    public synchronized int civTownCount(long civId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM towns WHERE civ_id = ?")) {
            statement.setLong(1, civId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public synchronized double civBeakersPerHour(long civId) throws SQLException {
        List<TownBaseProduction> towns = townBaseProductions(civId);
        double total = 0.0;
        for (TownBaseProduction town : towns) {
            total += townProduction(town.id(), town.baseHammersPerHour(), town.baseBeakersPerHour(), town.baseMoneyPerHour(), town.baseHappiness()).beakersPerHour();
        }
        return total;
    }

    /**
     * Pay a minute's worth of completed building production to every town bank.
     * Fractional coins are saved in SQLite, including across normal restarts.
     * The transaction ensures a single tick never credits only part of the towns.
     * There is no offline catch-up; the service only accrues while the server runs.
     */
    public synchronized long distributeTownIncomeMinute() throws SQLException {
        record IncomeBase(long id, double hammers, double beakers, double money, double happiness,
                          double remainder) {
        }
        List<IncomeBase> towns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, hammers_per_hour, beakers_per_hour, money_per_hour,
                       base_happiness, income_remainder FROM towns
                """+" WHERE "+com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.visible(connection,"towns","towns.id"));
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                towns.add(new IncomeBase(
                        rs.getLong("id"), rs.getDouble("hammers_per_hour"),
                        rs.getDouble("beakers_per_hour"), rs.getDouble("money_per_hour"),
                        rs.getDouble("base_happiness"), rs.getDouble("income_remainder")));
            }
        }

        long total = 0L;
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE towns SET income_remainder = ? WHERE id = ?
                    """)) {
                for (IncomeBase town : towns) {
                    TownProduction production = townProduction(
                            town.id(), town.hammers(), town.beakers(), town.money(), town.happiness());
                    double earned = Math.max(0.0, production.moneyPerHour()) / 60.0
                            + Math.max(0.0, town.remainder());
                    if (!Double.isFinite(earned) || earned >= 1_000_000_000_000.0) {
                        throw new SQLException("Invalid income for town #" + town.id());
                    }
                    long wholeCoins = (long) Math.floor(earned + 1.0e-9);
                    double fraction = Math.max(0.0, earned - wholeCoins);
                    long civ;double tax;
                    try(var s=connection.prepareStatement("SELECT t.civ_id,c.income_tax_rate FROM towns t JOIN civilizations c ON c.id=t.civ_id WHERE t.id=?")){s.setLong(1,town.id());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing income town");civ=r.getLong(1);tax=r.getDouble(2);}}
                    var payout=com.avrgaming.civcraft.modern.war.OccupationRules.income(wholeCoins,tax,com.avrgaming.civcraft.modern.war.OccupationRules.captured(connection,town.id()),capturedPenalty);
                    update.setDouble(1, fraction);
                    update.setLong(2, town.id());
                    update.executeUpdate();
                    com.avrgaming.civcraft.modern.war.OccupationRules.credit(connection,town.id(),civ,payout);
                    total = Math.addExact(total, payout.town());
                }
            }
            connection.commit();
            return total;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized double civMoneyPerHour(long civId) throws SQLException {
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
                    towns.add(new TownBaseProduction(rs.getLong("id"), rs.getDouble("hammers_per_hour"), rs.getDouble("beakers_per_hour"), rs.getDouble("money_per_hour"), rs.getDouble("base_happiness")));
                }
            }
        }
        return towns;
    }

    private TownProduction townProduction(long townId, double baseHammersPerHour, double baseBeakersPerHour, double baseMoneyPerHour, double baseHappiness) throws SQLException {
        var goodies=townGoodieEffects(townId);
        double structureHammers = 0.0;
        double beakers = 0.0;
        double money = 0.0;
        double structureHappiness = 0,structureUnhappiness=0,biomeHappiness=0;
        double education=0,growth=0;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT
                    COALESCE(SUM(production_hammers_per_hour), 0) AS hammers,
                    COALESCE(SUM(beakers_per_hour), 0) AS beakers,
                    COALESCE(SUM(money_per_hour), 0) AS money,
                    COALESCE(SUM(happiness), 0) AS happiness
                FROM active_structure_builds
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
        if(structureDefinitions!=null) {
            structureHammers=0;beakers=0;money=0;structureHappiness=0;
            int residents;
            try(var query=connection.prepareStatement("SELECT COUNT(*) FROM residents WHERE town_id=?")) {
                query.setLong(1,townId);try(var row=query.executeQuery()){residents=row.next()?row.getInt(1):0;}
            }
            try(var query=connection.prepareStatement("""
                    SELECT structure_id,COUNT(*),SUM(production_hammers_per_hour),SUM(beakers_per_hour),SUM(money_per_hour),SUM(happiness)
                    FROM active_structure_builds WHERE town_id=? AND status='COMPLETE' GROUP BY structure_id
                    """)) {
                query.setLong(1,townId);try(var rows=query.executeQuery()) {
                    while(rows.next()) {
                        String id=rows.getString(1);int count=rows.getInt(2);
                        var definition=structureDefinitions.find(id).orElse(null);
                        if(definition!=null) {
                            var dynamic=structureDefinitions.tradeAttributes(id).calculate(goodies.goods());
                            structureHammers+=(definition.hammersPerHourBonus()+dynamic.get("HAMMERS"))*count;beakers+=(definition.beakersPerHour()+dynamic.get("BEAKERS"))*count;
                            money+=(definition.moneyPerHour()+dynamic.get("MONEY"))*count;
                            var attributes=structureDefinitions.attributes(id);
                            double scalar=definition.happiness()-attributes.happiness();
                            structureHappiness+=(attributes.get("HAPPINESS")+dynamic.get("HAPPINESS")+Math.max(0,scalar))*count;
                            structureUnhappiness+=(attributes.get("UNHAPPINESS")+dynamic.get("UNHAPPINESS")+Math.max(0,-scalar))*count;
                            education+=(attributes.education(id,residents)+dynamic.education(id,residents))*count;
                            growth+=(attributes.get("GROWTH")+dynamic.get("GROWTH"))*count;
                        } else {
                            structureHammers+=rows.getDouble(3);beakers+=rows.getDouble(4);money+=rows.getDouble(5);structureHappiness+=Math.max(0,rows.getDouble(6));structureUnhappiness+=Math.max(0,-rows.getDouble(6));
                        }
                    }
                }
            }
        }
        if(cultureBiomes!=null)try(var query=connection.prepareStatement("SELECT b.biome,COUNT(*) FROM culture_regions cr JOIN culture_biome_cache b ON b.world=cr.world AND b.chunk_x=cr.chunk_x AND b.chunk_z=cr.chunk_z WHERE cr.town_id=? GROUP BY b.biome")) {
            query.setLong(1,townId);try(var rows=query.executeQuery()){while(rows.next()){var yield=cultureBiomes.yield(rows.getString(1));int count=rows.getInt(2);structureHammers+=yield.hammers()*count;beakers+=yield.beakers()*count;money+=yield.coins()*count;biomeHappiness+=yield.happiness()*count;growth+=yield.growth()*count;}}
        }
        structureHammers+=goodies.value("buff_construction");
        structureHappiness+=goodies.happiness();
        double happiness = Math.max(0,baseHappiness + structureHappiness + biomeHappiness);
        var mood=happinessRules==null?null:townMood(townId,baseHappiness+structureHappiness+biomeHappiness,structureUnhappiness);
        double multiplier=mood==null?1:mood.state().hammers();
        double scienceMultiplier=mood==null?multiplier:mood.state().science(),coinMultiplier=mood==null?multiplier:mood.state().coins();
        if(mood!=null)multiplier=mood.state().hammers();
        if(consumptionCatalog!=null)structureHammers+=com.avrgaming.civcraft.modern.town.MineProductionStore.hammers(connection,townId,consumptionCatalog);
        double hammerRate=1,beakerRate=1,growthRate=1;
        if(governments!=null) {
            var government=governments.require(com.avrgaming.civcraft.modern.war.OccupationRules.government(connection,townId));
            hammerRate=government.hammers();beakerRate=government.beakers();growthRate=government.growth();
        }
        boolean fertilizer=false;
        // The additional growth column is installed with the full happiness data.
        if(happinessRules!=null)try(var query=connection.prepareStatement("SELECT t.base_growth,EXISTS(SELECT 1 FROM civ_technologies ct WHERE ct.civ_id=t.civ_id AND ct.tech_id='tech_fertilizer') FROM towns t WHERE t.id=?")) {
            query.setLong(1,townId);try(var row=query.executeQuery()){if(row.next()){growth+=row.getDouble(1);fertilizer=row.getBoolean(2);}}
        }
        var wonder=townWonderBonuses(townId);
        growth=com.avrgaming.civcraft.modern.town.GrowthRules.total(growth,growthRate,fertilizer,wonder.growth()+goodies.value("buff_year_of_plenty")+wonder.grapeGrowth()*goodies.count("good_grapes"));
        return new TownProduction(
                Math.max(0.0,(Math.max(0.0, baseHammersPerHour) + Math.max(0.0, structureHammers)) * multiplier * hammerRate * (com.avrgaming.civcraft.modern.war.OccupationRules.captured(connection,townId)?capturedPenalty:1)),
                (Math.max(0.0, baseBeakersPerHour) + Math.max(0.0, beakers)) * Math.max(0,1+education) * scienceMultiplier * beakerRate * (1+wonder.science()+goodies.value("buff_innovation")),
                (Math.max(0.0, baseMoneyPerHour) + Math.max(0.0, money)) * coinMultiplier,
                mood==null?happiness:mood.happy(),
                mood==null?"Инициализация":mood.state().name(),
                multiplier,mood==null?0:mood.unhappy(),mood==null?0:mood.percentage(),mood==null?multiplier:mood.state().culture(),growth
        );
    }

    private com.avrgaming.civcraft.modern.town.HappinessRules.Result townMood(long town,double happy,double unhappy)throws SQLException {
        long civ,capital;int level,residents,count;
        try(var s=connection.prepareStatement("""
            SELECT t.civ_id,t.level,COALESCE(c.capital_town_id,(SELECT MIN(id) FROM towns WHERE civ_id=c.id)),
              (SELECT COUNT(*) FROM residents WHERE town_id=t.id),(SELECT COUNT(*) FROM towns WHERE civ_id=t.civ_id),t.base_unhappiness
            FROM towns t JOIN civilizations c ON c.id=t.civ_id WHERE t.id=?
            """)) {
            s.setLong(1,town);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing happiness town");civ=r.getLong(1);level=r.getInt(2);capital=r.getLong(3);residents=r.getInt(4);count=r.getInt(5);unhappy+=r.getDouble(6);}
        }
        happy+=happinessRules.base(level);unhappy+=happinessRules.population(residents,0,0)+happinessRules.empire(Math.max(0,count-1),0);
        if(town!=capital && !com.avrgaming.civcraft.modern.war.OccupationRules.captured(connection,town)) {
            record Hall(String world,int x,int y,int z) {}
            java.util.Map<Long,Hall> halls=new java.util.HashMap<>();
            try(var s=connection.prepareStatement("SELECT town_id,world,x,y,z FROM structure_builds WHERE town_id IN (?,?) AND structure_id IN ('s_capitol','s_townhall') AND status IN ('QUEUED','BUILDING','COMPLETE') ORDER BY id")) {
                s.setLong(1,town);s.setLong(2,capital);try(var r=s.executeQuery()){while(r.next())halls.putIfAbsent(r.getLong(1),new Hall(r.getString(2),r.getInt(3),r.getInt(4),r.getInt(5)));}
            }
            var a=halls.get(town);var b=halls.get(capital);
            if(a!=null && b!=null && a.world().equals(b.world())) {
                double distance=Math.sqrt(Math.pow((double)a.x()-b.x(),2)+Math.pow((double)a.y()-b.y(),2)+Math.pow((double)a.z()-b.z(),2));
                unhappy+=happinessRules.distance(distance,touchesCapital(civ,town,capital));
            }
        }
        return happinessRules.evaluate(happy,unhappy);
    }
    private boolean touchesCapital(long civ,long town,long capital)throws SQLException {
        java.util.Map<Long,java.util.Set<Long>> links=new java.util.HashMap<>();
        try(var s=connection.prepareStatement("""
            SELECT DISTINCT a.town_id,b.town_id FROM culture_regions a JOIN towns ta ON ta.id=a.town_id
            JOIN culture_regions b ON b.world=a.world AND ((b.chunk_x=a.chunk_x+1 AND b.chunk_z=a.chunk_z)
              OR (b.chunk_x=a.chunk_x AND b.chunk_z=a.chunk_z+1)) JOIN towns tb ON tb.id=b.town_id
            WHERE ta.civ_id=? AND tb.civ_id=? AND a.town_id<>b.town_id
            """)) {
            s.setLong(1,civ);s.setLong(2,civ);try(var r=s.executeQuery()){while(r.next()){long a=r.getLong(1),b=r.getLong(2);links.computeIfAbsent(a,id->new java.util.HashSet<>()).add(b);links.computeIfAbsent(b,id->new java.util.HashSet<>()).add(a);}}
        }
        var seen=new java.util.HashSet<Long>();var queue=new java.util.ArrayDeque<Long>();queue.add(town);
        while(!queue.isEmpty()){long id=queue.removeFirst();if(id==capital)return true;if(seen.add(id))queue.addAll(links.getOrDefault(id,java.util.Set.of()));}
        return false;
    }

    private record TownBaseProduction(long id, double baseHammersPerHour, double baseBeakersPerHour, double baseMoneyPerHour, double baseHappiness) {
    }

    private record TownProduction(double hammersPerHour, double beakersPerHour, double moneyPerHour, double happiness, String state, double multiplier,double unhappiness,double percentage,double cultureMultiplier,double growth) {
    }

    public synchronized int townCount(long civId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) AS count FROM towns WHERE civ_id = ?")) {
            statement.setLong(1, civId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt("count") : 0;
            }
        }
    }

    public synchronized void chargeAndStartResearch(UUID uuid, double amount, long civId, String techId) throws SQLException {
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

    public synchronized void chargeResident(UUID uuid, double amount) throws SQLException {
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

    public synchronized void depositResident(UUID uuid, double amount) throws SQLException {
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


    public synchronized void deleteCamp(long campId, UUID owner) throws SQLException {
        com.avrgaming.civcraft.modern.camp.CampLonghouseStore.requireUnlocked(connection,campId);
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

    public synchronized void deleteTown(long townId, UUID mayor) throws SQLException {
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

    public synchronized void deleteCivilization(long civId, UUID leader) throws SQLException {
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

    public synchronized void deleteStructureBuild(long buildId) throws SQLException {
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
        TownProduction production = townProduction(townId, rs.getDouble("hammers_per_hour"), rs.getDouble("beakers_per_hour"), rs.getDouble("money_per_hour"), rs.getDouble("base_happiness"));
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
                production.multiplier(),production.unhappiness(),production.percentage(),production.cultureMultiplier(),production.growth()
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

    public synchronized boolean isFirstCompletedStructureInTown(long buildId, long townId) throws SQLException {
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
    public synchronized void close() {
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
