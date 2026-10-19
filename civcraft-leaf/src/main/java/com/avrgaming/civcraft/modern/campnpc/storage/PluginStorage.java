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
    private final Path legacyPath;

    public PluginStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.legacyPath = plugin.getDataFolder().toPath().resolve("data.sqlite").toAbsolutePath().normalize();
        this.databasePath = plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin modern
                ? modern.settings().sqlitePath(plugin.getDataFolder().toPath()).toAbsolutePath().normalize() : legacyPath;
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
            boolean completer=false;
            try(var rs=statement.executeQuery("PRAGMA table_info(camp_quest_progress)")){while(rs.next())if(rs.getString("name").equals("completer_uuid"))completer=true;}
            if(!completer)statement.executeUpdate("ALTER TABLE camp_quest_progress ADD COLUMN completer_uuid TEXT");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS camp_npc_migrations(id TEXT PRIMARY KEY)");
            migrateLegacy(connection);
            if(plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin)com.avrgaming.civcraft.modern.campnpc.quest.QuestSubmissionStore.install(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to initialize camp NPC storage",exception);
        }
    }

    public Connection connect() throws SQLException {
        Connection connection=DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
        try(Statement statement=connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");statement.execute("PRAGMA foreign_keys=ON");
        } catch(SQLException failure){connection.close();throw failure;}
        return connection;
    }

    /** Copy the former separate database once, without modifying or removing it. */
    private void migrateLegacy(Connection target)throws SQLException {
        if(databasePath.equals(legacyPath))return;
        try(var statement=target.prepareStatement("SELECT 1 FROM camp_npc_migrations WHERE id=?")) {
            statement.setString(1,legacyPath.toString());try(var rows=statement.executeQuery()){if(rows.next())return;}
        }
        target.setAutoCommit(false);
        try {
            if(java.nio.file.Files.isRegularFile(legacyPath)) {
                try(var source=DriverManager.getConnection("jdbc:sqlite:"+legacyPath);var statement=source.createStatement()) {
                    statement.execute("PRAGMA query_only=ON");
                    for(String table:java.util.List.of("camp_quest_state","camp_quest_progress","camp_npc_bonuses")) {
                        var columns=new java.util.ArrayList<String>();
                        try(var rows=statement.executeQuery("PRAGMA table_info("+table+")")){while(rows.next())columns.add(rows.getString("name"));}
                        if(columns.isEmpty())continue;
                        var allowed=switch(table) {
                            case "camp_quest_state" -> java.util.List.of("camp_id","offer_ids","active_ids","generated_at","last_reroll_at");
                            case "camp_quest_progress" -> java.util.List.of("camp_id","quest_id","amount","completed","rewarded","completer_uuid");
                            default -> java.util.List.of("camp_id","health_bonus","damage_bonus");
                        };
                        columns.retainAll(allowed);
                        String names=String.join(",",columns),marks=String.join(",",java.util.Collections.nCopies(columns.size(),"?"));
                        try(var rows=statement.executeQuery("SELECT "+names+" FROM "+table);var insert=target.prepareStatement("INSERT OR IGNORE INTO "+table+"("+names+") VALUES("+marks+")")) {
                            while(rows.next()){for(int i=0;i<columns.size();i++)insert.setObject(i+1,rows.getObject(i+1));insert.addBatch();}insert.executeBatch();
                        }
                    }
                }
            }
            try(var marker=target.prepareStatement("INSERT INTO camp_npc_migrations VALUES(?)")){marker.setString(1,legacyPath.toString());marker.executeUpdate();}
            target.commit();
        }catch(SQLException|RuntimeException failure){target.rollback();throw failure;}
        finally{target.setAutoCommit(true);}
    }

    public void close() {
    }
}
