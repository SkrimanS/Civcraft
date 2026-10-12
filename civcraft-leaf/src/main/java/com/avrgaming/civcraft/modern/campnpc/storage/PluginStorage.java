package com.avrgaming.civcraft.modern.campnpc.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.bukkit.plugin.java.JavaPlugin;

public final class PluginStorage {
    private final JavaPlugin plugin;
    private final Path databasePath;

    public PluginStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.databasePath = plugin.getDataFolder().toPath().resolve("data.sqlite");
    }

    public void initialize() {
        plugin.getDataFolder().mkdirs();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS camp_quest_state (
                        camp_id INTEGER PRIMARY KEY,
                        offer_ids TEXT NOT NULL DEFAULT '',
                        active_ids TEXT NOT NULL DEFAULT '',
                        generated_at INTEGER NOT NULL DEFAULT 0,
                        last_reroll_at INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS camp_quest_progress (
                        camp_id INTEGER NOT NULL,
                        quest_id TEXT NOT NULL,
                        amount INTEGER NOT NULL DEFAULT 0,
                        completed INTEGER NOT NULL DEFAULT 0,
                        rewarded INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (camp_id, quest_id)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS camp_npc_bonuses (
                        camp_id INTEGER PRIMARY KEY,
                        health_bonus REAL NOT NULL DEFAULT 0,
                        damage_bonus REAL NOT NULL DEFAULT 0
                    )
                    """);
        } catch (SQLException exception) {
            plugin.getLogger().severe("Unable to initialize storage: " + exception.getMessage());
        }
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
    }

    public void close() {
    }
}
