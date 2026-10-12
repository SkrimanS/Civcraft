package com.avrgaming.civcraft.modern.foundation;

import com.avrgaming.civcraft.modern.build.LegacyStructureService;
import com.avrgaming.civcraft.modern.campnpc.lang.Lang;
import com.avrgaming.civcraft.modern.config.ModernCivCraftSettings;
import com.avrgaming.civcraft.modern.domain.CampRecord;
import com.avrgaming.civcraft.modern.domain.CivRecord;
import com.avrgaming.civcraft.modern.domain.TownRecord;
import com.avrgaming.civcraft.modern.service.CivCraftGameService;
import com.avrgaming.civcraft.modern.service.TabPrefixService;
import com.avrgaming.civcraft.modern.service.Validation;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class FoundationItemListener implements Listener {
    private final JavaPlugin plugin;
    private final CivCraftGameService game;
    private final LegacyStructureService structures;
    private final TabPrefixService tabPrefixes;
    private final Lang lang;
    private ModernCivCraftSettings settings;
    private final Map<UUID, PendingFoundation> pending = new ConcurrentHashMap<>();

    public FoundationItemListener(JavaPlugin plugin, CivCraftGameService game, LegacyStructureService structures, TabPrefixService tabPrefixes, ModernCivCraftSettings settings) {
        this(plugin, game, structures, tabPrefixes, settings, new Lang(plugin));
    }

    public FoundationItemListener(JavaPlugin plugin, CivCraftGameService game, LegacyStructureService structures, TabPrefixService tabPrefixes, ModernCivCraftSettings settings, Lang lang) {
        this.plugin = plugin;
        this.game = game;
        this.structures = structures;
        this.tabPrefixes = tabPrefixes;
        this.settings = settings;
        this.lang = lang;
    }

    public void updateSettings(ModernCivCraftSettings settings) {
        this.settings = settings;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (pending.containsKey(player.getUniqueId())) {
            PendingFoundation current = pending.get(player.getUniqueId());
            player.sendMessage(component(current != null && current.awaitingPreview
                    ? "foundation.pending-preview"
                    : "foundation.pending-input",
                    current != null && current.awaitingPreview
                            ? "&eСначала напишите yes или no для превью."
                            : "&eСначала завершите текущее основание. Введите данные в чат."));
            event.setCancelled(true);
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir()) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        Location placeLocation = clicked.getRelative(event.getBlockFace() == null ? BlockFace.UP : event.getBlockFace()).getLocation();

        if (matches(item, settings.campFoundationMmoItemId(), settings.campFoundationMmoItemType())) {
            event.setCancelled(true);
            ItemStack consumed = consumeOneMainHand(player);
            pending.put(player.getUniqueId(), new PendingFoundation(FoundationKind.CAMP, placeLocation, consumed));
            player.sendMessage(component("foundation.enter-camp-name", "&eВведите название лагеря. От 5 до 16 символов."));
            return;
        }
        if (matches(item, settings.civFlagMmoItemId(), settings.civFlagMmoItemType())) {
            event.setCancelled(true);
            ItemStack consumed = consumeOneMainHand(player);
            pending.put(player.getUniqueId(), new PendingFoundation(FoundationKind.CIVILIZATION, placeLocation, consumed));
            player.sendMessage(component("foundation.enter-civ-name", "&eВведите название цивилизации. От 5 до 16 символов."));
            return;
        }
        if (matches(item, settings.settlerMmoItemId(), settings.settlerMmoItemType())) {
            event.setCancelled(true);
            ItemStack consumed = consumeOneMainHand(player);
            pending.put(player.getUniqueId(), new PendingFoundation(FoundationKind.TOWN, placeLocation, consumed));
            player.sendMessage(component("foundation.enter-town-name", "&eВведите название города. От 5 до 16 символов."));
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        PendingFoundation data = pending.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        plugin.getServer().getScheduler().runTask(plugin, () -> handleInput(player, message));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PendingFoundation data = pending.remove(event.getPlayer().getUniqueId());
        if (data != null) {
            structures.cancelPreview(event.getPlayer(), true);
            restoreConsumedItem(event.getPlayer(), data);
        }
    }

    private void handleInput(Player player, String input) {
        PendingFoundation data = pending.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        try {
            if (data.awaitingPreview) {
                handlePreviewAnswer(player, data, input);
                return;
            }
            switch (data.kind) {
                case CAMP -> handleCampName(player, data, input);
                case TOWN -> handleTownName(player, data, input);
                case CIVILIZATION -> handleCivilizationInput(player, data, input);
            }
        } catch (IllegalArgumentException | SQLException exception) {
            rollbackCreatedObjects(player, data);
            structures.cancelPreview(player, true);
            restoreAndRemovePending(player, data);
            player.sendMessage(component("foundation.error", "&cОшибка: {error}", "error", exception.getMessage()));
        } catch (Exception exception) {
            rollbackCreatedObjects(player, data);
            structures.cancelPreview(player, true);
            restoreAndRemovePending(player, data);
            plugin.getLogger().warning("Foundation item failed for " + player.getName() + ": " + exception.getMessage());
            player.sendMessage(component("foundation.create-error", "&cОшибка создания основания: {error}", "error", exception.getMessage()));
        }
    }

    private void handlePreviewAnswer(Player player, PendingFoundation data, String input) throws Exception {
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("yes") && !normalized.equals("no")) {
            player.sendMessage(component("foundation.preview-confirm-again", "&eВведите yes, чтобы подтвердить основание, или no, чтобы отменить."));
            return;
        }
        if (normalized.equals("no")) {
            structures.cancelPreview(player, false);
            restoreAndRemovePending(player, data);
            player.sendMessage(component("foundation.cancelled", "&eОснование отменено. Предмет возвращен."));
            return;
        }
        structures.cancelPreview(player, true);
        confirmFoundation(player, data);
    }

    private void handleCampName(Player player, PendingFoundation data, String name) throws Exception {
        if (!Validation.isValidFoundationName(name)) {
            player.sendMessage(component("foundation.invalid-camp-name", "&cНазвание лагеря должно быть от 5 до 16 символов и без спецсимволов. Введите снова."));
            return;
        }
        data.campName = name;
        data.previewOrigin = structures.previewFoundationStructure(player, settings.campStructureId(), data.location, settings.legacyDefaultDirection());
        data.awaitingPreview = true;
        player.sendMessage(component("foundation.camp-preview", "&eПревью лагеря показано только вам. Напишите yes, чтобы основать лагерь, или no, чтобы отменить."));
    }

    private void handleTownName(Player player, PendingFoundation data, String name) throws Exception {
        if (!Validation.isValidFoundationName(name)) {
            player.sendMessage(component("foundation.invalid-town-name", "&cНазвание города должно быть от 5 до 16 символов и без спецсимволов. Введите снова."));
            return;
        }
        data.townName = name;
        data.previewOrigin = structures.previewFoundationStructure(player, settings.townHallStructureId(), data.location, settings.legacyDefaultDirection());
        data.awaitingPreview = true;
        player.sendMessage(component("foundation.town-preview", "&eПревью ратуши показано только вам. Напишите yes, чтобы основать город, или no, чтобы отменить."));
    }

    private void handleCivilizationInput(Player player, PendingFoundation data, String input) throws Exception {
        if (data.civName == null) {
            if (!Validation.isValidFoundationName(input)) {
                player.sendMessage(component("foundation.invalid-civ-name", "&cНазвание цивилизации должно быть от 5 до 16 символов и без спецсимволов. Введите снова."));
                return;
            }
            data.civName = input;
            player.sendMessage(component("foundation.enter-civ-tag", "&eВведите тег цивилизации. От 3 до 5 символов."));
            return;
        }
        if (data.civTag == null) {
            if (!Validation.isValidCivTag(input)) {
                player.sendMessage(component("foundation.invalid-civ-tag", "&cТег цивилизации должен быть от 3 до 5 символов и без спецсимволов. Введите снова."));
                return;
            }
            data.civTag = input.toUpperCase(Locale.ROOT);
            player.sendMessage(component("foundation.enter-capital-name", "&eВведите название столицы. От 5 до 16 символов."));
            return;
        }
        if (!Validation.isValidFoundationName(input)) {
            player.sendMessage(component("foundation.invalid-capital-name", "&cНазвание столицы должно быть от 5 до 16 символов и без спецсимволов. Введите снова."));
            return;
        }
        data.capitalName = input;
        data.previewOrigin = structures.previewFoundationStructure(player, settings.capitolStructureId(), data.location, settings.legacyDefaultDirection());
        data.awaitingPreview = true;
        player.sendMessage(component("foundation.capital-preview", "&eПревью капитолия показано только вам. Напишите yes, чтобы основать цивилизацию, или no, чтобы отменить."));
    }

    private void confirmFoundation(Player player, PendingFoundation data) throws Exception {
        Location origin = data.previewOrigin == null ? data.location : data.previewOrigin;
        switch (data.kind) {
            case CAMP -> {
                CampRecord camp = game.createCampAt(player, data.campName, origin);
                data.createdCamp = camp;
                structures.buildCampStructureAtOrigin(player, origin);
                data.success = true;
                tabPrefixes.update(player);
                pending.remove(player.getUniqueId());
                player.sendMessage(component("foundation.camp-created", "&aЛагерь {name} основан.", "name", camp.name()));
            }
            case TOWN -> {
                TownRecord town = game.createTownAt(player, data.townName, origin);
                data.createdTown = town;
                structures.buildTownHallStructureAtOrigin(player, town, origin);
                data.success = true;
                pending.remove(player.getUniqueId());
                player.sendMessage(component("foundation.town-created", "&aГород {name} основан. Ратуша построена.", "name", town.name()));
            }
            case CIVILIZATION -> {
                CivRecord civ = game.createCivilization(player, data.civName, data.civTag);
                data.createdCiv = civ;
                TownRecord capital = game.createTownAt(player, data.capitalName, origin);
                data.createdTown = capital;
                structures.buildCapitalStructureAtOrigin(player, capital, origin);
                data.success = true;
                pending.remove(player.getUniqueId());
                player.sendMessage(component("foundation.civ-created", "&aЦивилизация {name} [{tag}] основана. Столица: {capital}. Капитолий построен.", "name", civ.name(), "tag", civ.tag(), "capital", capital.name()));
            }
        }
    }

    private ItemStack consumeOneMainHand(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        ItemStack consumed = item.clone();
        consumed.setAmount(1);
        int amount = item.getAmount();
        if (amount <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            item.setAmount(amount - 1);
            player.getInventory().setItemInMainHand(item);
        }
        player.updateInventory();
        return consumed;
    }

    private void restoreAndRemovePending(Player player, PendingFoundation data) {
        pending.remove(player.getUniqueId());
        restoreConsumedItem(player, data);
    }

    private void restoreConsumedItem(Player player, PendingFoundation data) {
        if (data == null || data.success || data.consumedItem == null || data.consumedItem.getType().isAir()) {
            return;
        }
        HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(data.consumedItem.clone());
        for (ItemStack item : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
        player.updateInventory();
    }

    private void rollbackCreatedObjects(Player player, PendingFoundation data) {
        if (data == null || data.success) {
            return;
        }
        if (data.createdTown != null) {
            try {
                game.rollbackCreatedTown(player, data.createdTown);
            } catch (SQLException exception) {
                plugin.getLogger().warning("Unable to rollback town " + data.createdTown.id() + ": " + exception.getMessage());
            }
            data.createdTown = null;
        }
        if (data.createdCiv != null) {
            try {
                game.rollbackCreatedCivilization(player, data.createdCiv);
            } catch (SQLException exception) {
                plugin.getLogger().warning("Unable to rollback civilization " + data.createdCiv.id() + ": " + exception.getMessage());
            }
            data.createdCiv = null;
        }
        if (data.createdCamp != null) {
            try {
                game.rollbackCreatedCamp(player, data.createdCamp);
            } catch (SQLException exception) {
                plugin.getLogger().warning("Unable to rollback camp " + data.createdCamp.id() + ": " + exception.getMessage());
            }
            data.createdCamp = null;
        }
    }

    private boolean matches(ItemStack item, String expectedId, String expectedType) {
        if (expectedId == null || expectedId.isBlank()) {
            return false;
        }
        String id = readMmoItemTag(item, "MMOITEMS_ITEM_ID");
        String type = readMmoItemTag(item, "MMOITEMS_ITEM_TYPE");
        boolean idMatches = expectedId.equalsIgnoreCase(id);
        boolean typeMatches = expectedType == null || expectedType.isBlank() || expectedType.equalsIgnoreCase(type);
        return idMatches && typeMatches;
    }

    private String readMmoItemTag(ItemStack item, String key) {
        String pdc = readPdc(item, key);
        if (!pdc.isBlank()) {
            return pdc;
        }
        try {
            Class<?> nbtItemClass = Class.forName("io.lumine.mythic.lib.api.item.NBTItem");
            Method get = nbtItemClass.getMethod("get", ItemStack.class);
            Object nbtItem = get.invoke(null, item);
            Method getString = nbtItemClass.getMethod("getString", String.class);
            Object value = getString.invoke(nbtItem, key);
            return value == null ? "" : String.valueOf(value);
        } catch (ReflectiveOperationException ignored) {
            return "";
        }
    }

    private String readPdc(ItemStack item, String wantedKey) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return "";
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        String normalized = wantedKey.toLowerCase(Locale.ROOT).replace('_', '-');
        for (NamespacedKey key : container.getKeys()) {
            String keyName = key.getKey().toLowerCase(Locale.ROOT);
            if (keyName.equals(normalized) || keyName.equals(wantedKey.toLowerCase(Locale.ROOT))) {
                String value = container.get(key, PersistentDataType.STRING);
                return value == null ? "" : value;
            }
        }
        return "";
    }

    private net.kyori.adventure.text.Component component(String key, String fallback, Object... replacements) {
        return lang.component(key, fallback, replacements);
    }

    private enum FoundationKind {
        CAMP,
        CIVILIZATION,
        TOWN
    }

    private static final class PendingFoundation {
        private final FoundationKind kind;
        private final Location location;
        private final ItemStack consumedItem;
        private String campName;
        private String townName;
        private String civName;
        private String civTag;
        private String capitalName;
        private Location previewOrigin;
        private boolean awaitingPreview;
        private CampRecord createdCamp;
        private CivRecord createdCiv;
        private TownRecord createdTown;
        private boolean success;

        private PendingFoundation(FoundationKind kind, Location location, ItemStack consumedItem) {
            this.kind = kind;
            this.location = location;
            this.consumedItem = consumedItem;
        }
    }
}
