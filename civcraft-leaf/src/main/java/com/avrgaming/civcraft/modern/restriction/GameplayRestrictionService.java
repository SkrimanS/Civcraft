package com.avrgaming.civcraft.modern.restriction;

import com.avrgaming.civcraft.modern.config.ModernCivCraftSettings;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExpEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ExpBottleEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.java.JavaPlugin;

public final class GameplayRestrictionService implements Listener {
    private final JavaPlugin plugin;
    private final Map<UUID, Long> messageCooldowns = new HashMap<>();
    private volatile ModernCivCraftSettings settings;
    private YamlConfiguration restrictions;
    private YamlConfiguration lang;
    private final Set<Material> blockedRecipeResults = EnumSet.noneOf(Material.class);
    private final Set<Material> redstoneMaterials = EnumSet.noneOf(Material.class);
    private List<String> plantAllowedStructureIds = List.of("farm", "city_farm");
    private List<String> redstoneAllowedStructureIds = List.of("farm", "city_farm");

    public GameplayRestrictionService(JavaPlugin plugin, ModernCivCraftSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public void start() {
        saveResourceIfMissing("restrictions.yml");
        reload(settings);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTask(plugin, this::removeBlockedRecipes);
    }

    public void reload(ModernCivCraftSettings settings) {
        this.settings = settings;
        this.restrictions = YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("restrictions.yml").toFile());
        File langFile = plugin.getDataFolder().toPath().resolve("lang/ru.yml").toFile();
        this.lang = YamlConfiguration.loadConfiguration(langFile);
        rebuildBlockedRecipeResults();
        rebuildRedstoneMaterials();
        this.plantAllowedStructureIds = lowerList(restrictions.getStringList("plants.allowed-structure-ids"));
        if (plantAllowedStructureIds.isEmpty()) {
            this.plantAllowedStructureIds = List.of("farm", "city_farm");
        }
        this.redstoneAllowedStructureIds = lowerList(restrictions.getStringList("redstone.allowed-structure-ids"));
        if (redstoneAllowedStructureIds.isEmpty()) {
            this.redstoneAllowedStructureIds = List.of("farm", "city_farm");
        }
        Bukkit.getScheduler().runTask(plugin, this::removeBlockedRecipes);
    }

    private void rebuildBlockedRecipeResults() {
        blockedRecipeResults.clear();
        for (String name : restrictions.getStringList("recipes.banned-results")) {
            addMaterial(blockedRecipeResults, name);
        }
        if (restrictions.getBoolean("recipes.ban-anvils", true)) {
            addMaterial(blockedRecipeResults, "ANVIL");
            addMaterial(blockedRecipeResults, "CHIPPED_ANVIL");
            addMaterial(blockedRecipeResults, "DAMAGED_ANVIL");
        }
        if (restrictions.getBoolean("recipes.ban-enchanting-table", true)) {
            addMaterial(blockedRecipeResults, "ENCHANTING_TABLE");
        }
        if (restrictions.getBoolean("recipes.ban-hopper", true)) {
            addMaterial(blockedRecipeResults, "HOPPER");
        }
        if (restrictions.getBoolean("recipes.ban-all-armor", true)) {
            for (String prefix : List.of("LEATHER", "CHAINMAIL", "IRON", "GOLDEN", "DIAMOND", "NETHERITE")) {
                addMaterial(blockedRecipeResults, prefix + "_HELMET");
                addMaterial(blockedRecipeResults, prefix + "_CHESTPLATE");
                addMaterial(blockedRecipeResults, prefix + "_LEGGINGS");
                addMaterial(blockedRecipeResults, prefix + "_BOOTS");
            }
            addMaterial(blockedRecipeResults, "TURTLE_HELMET");
            addMaterial(blockedRecipeResults, "SHIELD");
        }
        if (restrictions.getBoolean("recipes.ban-all-swords", true)) {
            for (String prefix : List.of("WOODEN", "STONE", "IRON", "GOLDEN", "DIAMOND", "NETHERITE")) {
                addMaterial(blockedRecipeResults, prefix + "_SWORD");
            }
        }
        if (restrictions.getBoolean("recipes.ban-bows", true)) {
            addMaterial(blockedRecipeResults, "BOW");
            addMaterial(blockedRecipeResults, "CROSSBOW");
        }
        if (restrictions.getBoolean("recipes.ban-redstone-results", true)) {
            rebuildRedstoneMaterials();
            blockedRecipeResults.addAll(redstoneMaterials);
        }
    }

    private void rebuildRedstoneMaterials() {
        redstoneMaterials.clear();
        for (String name : restrictions.getStringList("redstone.materials")) {
            addMaterial(redstoneMaterials, name);
        }
        if (!redstoneMaterials.isEmpty()) {
            return;
        }
        for (Material material : Material.values()) {
            String name = material.name();
            if (name.contains("REDSTONE")
                    || name.contains("PISTON")
                    || name.equals("HOPPER")
                    || name.equals("DISPENSER")
                    || name.equals("DROPPER")
                    || name.equals("OBSERVER")
                    || name.equals("COMPARATOR")
                    || name.equals("REPEATER")
                    || name.equals("LEVER")
                    || name.equals("DAYLIGHT_DETECTOR")
                    || name.equals("TRIPWIRE_HOOK")
                    || name.endsWith("_PRESSURE_PLATE")
                    || name.endsWith("_BUTTON")
                    || name.equals("POWERED_RAIL")
                    || name.equals("DETECTOR_RAIL")
                    || name.equals("ACTIVATOR_RAIL")
                    || name.equals("TARGET")) {
                redstoneMaterials.add(material);
            }
        }
    }

    public void removeBlockedRecipes() {
        if (!restrictions.getBoolean("recipes.enabled", true)) {
            return;
        }
        Iterator<Recipe> iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            Recipe recipe = iterator.next();
            if (recipe != null && recipe.getResult() != null && isBlockedRecipeResult(recipe.getResult().getType())) {
                iterator.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (!restrictions.getBoolean("recipes.enabled", true) || event.getRecipe() == null || event.getRecipe().getResult() == null) {
            return;
        }
        if (isBlockedRecipeResult(event.getRecipe().getResult().getType())) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!restrictions.getBoolean("recipes.enabled", true) || event.getRecipe() == null || event.getRecipe().getResult() == null) {
            return;
        }
        if (isBlockedRecipeResult(event.getRecipe().getResult().getType())) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                deny(player, "craft-blocked", "&cЭтот крафт запрещён.");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerExpChange(PlayerExpChangeEvent event) {
        if (restrictions.getBoolean("experience.disable-gain", true) && event.getAmount() > 0) {
            event.setAmount(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExp(BlockExpEvent event) {
        if (restrictions.getBoolean("experience.disable-gain", true)) {
            event.setExpToDrop(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (restrictions.getBoolean("experience.disable-gain", true)) {
            event.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExpBottle(ExpBottleEvent event) {
        if (restrictions.getBoolean("experience.disable-gain", true)) {
            event.setExperience(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        if (restrictions.getBoolean("plants.disable-growth", true) && !isPlantGrowthAllowed(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        if (!restrictions.getBoolean("plants.disable-growth", true)) {
            return;
        }
        Material newType = event.getNewState().getType();
        if (isPlantLike(newType) && !isPlantGrowthAllowed(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        if (restrictions.getBoolean("plants.disable-growth", true) && !isPlantGrowthAllowed(event.getLocation().getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockFertilize(BlockFertilizeEvent event) {
        if (!restrictions.getBoolean("plants.disable-growth", true)) {
            return;
        }
        Block block = event.getBlock();
        for (BlockState state : event.getBlocks()) {
            if (isPlantLike(state.getType()) && !isPlantGrowthAllowed(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        if (restrictions.getBoolean("redstone.disable-outside-allowed-structures", true) && !isRedstoneAllowed(event.getBlock())) {
            event.setNewCurrent(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!restrictions.getBoolean("redstone.disable-outside-allowed-structures", true)) {
            return;
        }
        if (isRedstoneMaterial(event.getBlockPlaced().getType()) && !isRedstoneAllowed(event.getBlockPlaced())) {
            event.setCancelled(true);
            deny(event.getPlayer(), "redstone-blocked", "&cРедстоун разрешён только в чанках построек фермы.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!restrictions.getBoolean("redstone.disable-outside-allowed-structures", true) || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        if ((event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.PHYSICAL) && isRedstoneMaterial(block.getType()) && !isRedstoneAllowed(block)) {
            event.setCancelled(true);
            deny(event.getPlayer(), "redstone-blocked", "&cРедстоун разрешён только в чанках построек фермы.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (restrictions.getBoolean("redstone.disable-outside-allowed-structures", true) && !isRedstoneAllowed(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (restrictions.getBoolean("redstone.disable-outside-allowed-structures", true) && !isRedstoneAllowed(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDispense(BlockDispenseEvent event) {
        if (restrictions.getBoolean("redstone.disable-outside-allowed-structures", true) && isRedstoneMaterial(event.getBlock().getType()) && !isRedstoneAllowed(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (!restrictions.getBoolean("redstone.disable-outside-allowed-structures", true)) {
            return;
        }
        Inventory initiator = event.getInitiator();
        if (initiator.getLocation() != null) {
            Block block = initiator.getLocation().getBlock();
            if (isRedstoneMaterial(block.getType()) && !isRedstoneAllowed(block)) {
                event.setCancelled(true);
            }
        }
    }

    private boolean isBlockedRecipeResult(Material material) {
        return blockedRecipeResults.contains(material);
    }

    private boolean isRedstoneMaterial(Material material) {
        return redstoneMaterials.contains(material);
    }

    private boolean isPlantGrowthAllowed(Block block) {
        if (!restrictions.getBoolean("plants.require-farmland", true)) {
            return isUpgradedCampFarmChunk(block) || isInStructureChunk(block, plantAllowedStructureIds);
        }
        boolean farmland = block.getRelative(0, -1, 0).getType() == Material.FARMLAND || block.getType() == Material.FARMLAND;
        return farmland && (isUpgradedCampFarmChunk(block) || isInStructureChunk(block, plantAllowedStructureIds));
    }

    private boolean isRedstoneAllowed(Block block) {
        return isInStructureChunk(block, redstoneAllowedStructureIds);
    }

    private boolean isUpgradedCampFarmChunk(Block block) {
        if (!restrictions.getBoolean("plants.allow-upgraded-camp-farm", true)) {
            return false;
        }
        World world = block.getWorld();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM structure_chunks sc
                JOIN structure_builds sb ON sb.id = sc.build_id
                JOIN camps c ON c.owner_uuid = sb.builder_uuid OR (c.world = sb.world AND c.x = sb.x AND c.y = sb.y AND c.z = sb.z)
                WHERE sc.world = ?
                  AND sc.chunk_x = ?
                  AND sc.chunk_z = ?
                  AND sb.status <> 'DEMOLISHED'
                  AND sb.structure_id = ?
                  AND c.farm_upgrade <> 0
                LIMIT 1
                """)) {
            statement.setString(1, world.getName());
            statement.setInt(2, block.getChunk().getX());
            statement.setInt(3, block.getChunk().getZ());
            statement.setString(4, settings.campStructureId());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to check upgraded camp farm chunk: " + exception.getMessage());
            return false;
        }
    }

    private boolean isInStructureChunk(Block block, Collection<String> structureIds) {
        if (structureIds == null || structureIds.isEmpty()) {
            return false;
        }
        String placeholders = String.join(",", structureIds.stream().map(ignored -> "?").toList());
        String sql = """
                SELECT 1
                FROM structure_chunks sc
                JOIN structure_builds sb ON sb.id = sc.build_id
                WHERE sc.world = ?
                  AND sc.chunk_x = ?
                  AND sc.chunk_z = ?
                  AND sb.status <> 'DEMOLISHED'
                  AND lower(sb.structure_id) IN (%s)
                LIMIT 1
                """.formatted(placeholders);
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, block.getWorld().getName());
            statement.setInt(2, block.getChunk().getX());
            statement.setInt(3, block.getChunk().getZ());
            int index = 4;
            for (String structureId : structureIds) {
                statement.setString(index++, structureId.toLowerCase(Locale.ROOT));
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Unable to check structure chunk restriction: " + exception.getMessage());
            return false;
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

    private boolean isPlantLike(Material material) {
        String name = material.name();
        return name.equals("WHEAT")
                || name.equals("CARROTS")
                || name.equals("POTATOES")
                || name.equals("BEETROOTS")
                || name.equals("NETHER_WART")
                || name.equals("COCOA")
                || name.equals("SWEET_BERRY_BUSH")
                || name.equals("CAVE_VINES")
                || name.equals("CAVE_VINES_PLANT")
                || name.equals("KELP")
                || name.equals("KELP_PLANT")
                || name.equals("BAMBOO")
                || name.equals("BAMBOO_SAPLING")
                || name.equals("SUGAR_CANE")
                || name.equals("CACTUS")
                || name.equals("MELON_STEM")
                || name.equals("PUMPKIN_STEM")
                || name.equals("ATTACHED_MELON_STEM")
                || name.equals("ATTACHED_PUMPKIN_STEM")
                || name.endsWith("_SAPLING")
                || name.equals("MANGROVE_PROPAGULE")
                || name.endsWith("_MUSHROOM")
                || name.equals("VINE")
                || name.equals("GLOW_LICHEN")
                || name.equals("SEAGRASS")
                || name.equals("TALL_SEAGRASS")
                || name.equals("SMALL_DRIPLEAF")
                || name.equals("BIG_DRIPLEAF")
                || name.equals("CHORUS_FLOWER")
                || name.equals("CHORUS_PLANT");
    }

    private void deny(Player player, String key, String fallback) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        Long last = messageCooldowns.get(uuid);
        if (last != null && now - last < 1000L) {
            return;
        }
        messageCooldowns.put(uuid, now);
        player.sendMessage(color(lang.getString("restrictions." + key, fallback)));
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    private List<String> lowerList(List<String> input) {
        List<String> result = new ArrayList<>();
        for (String value : input) {
            if (value != null && !value.isBlank()) {
                result.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    private void addMaterial(Set<Material> set, String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            set.add(Material.valueOf(name.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            plugin.getLogger().warning("Unknown material in restrictions config: " + name);
        }
    }

    private void saveResourceIfMissing(String path) {
        File file = plugin.getDataFolder().toPath().resolve(path).toFile();
        if (!file.exists()) {
            plugin.saveResource(path, false);
        }
    }
}
