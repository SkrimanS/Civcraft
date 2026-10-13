package com.avrgaming.civcraft.modern.command;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.build.LegacyStructureService;
import com.avrgaming.civcraft.modern.build.QueuedLegacyBuild;
import com.avrgaming.civcraft.modern.camp.CampService;
import com.avrgaming.civcraft.modern.campnpc.CampNpcMenusService;
import com.avrgaming.civcraft.modern.campnpc.lang.Lang;
import com.avrgaming.civcraft.modern.domain.CivRecord;
import com.avrgaming.civcraft.modern.domain.ResidentProfile;
import com.avrgaming.civcraft.modern.domain.TownClaimRecord;
import com.avrgaming.civcraft.modern.domain.TownRecord;
import com.avrgaming.civcraft.modern.economy.CivCraftEconomyService;
import com.avrgaming.civcraft.modern.foundation.FoundationItemFactory;
import com.avrgaming.civcraft.modern.research.ResearchService;
import com.avrgaming.civcraft.modern.research.TechDefinition;
import com.avrgaming.civcraft.modern.service.CivCraftGameService;
import com.avrgaming.civcraft.modern.service.SettlementMembershipService;
import com.avrgaming.civcraft.modern.service.TownClaimService;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.HashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class CivCraftCommand implements CommandExecutor, TabCompleter {
    private final CivCraftModernPlugin plugin;
    private final CivCraftGameService game;
    private final LegacyStructureService legacyStructures;
    private final ResearchService research;
    private final TownClaimService townClaims;
    private final SettlementMembershipService memberships;
    private final CivCraftEconomyService economy;
    private final CampService campService;
    private final CampNpcMenusService campNpcMenus;
    private final Lang lang;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    public CivCraftCommand(CivCraftModernPlugin plugin, CivCraftGameService game, LegacyStructureService legacyStructures, ResearchService research, TownClaimService townClaims, SettlementMembershipService memberships, CivCraftEconomyService economy, CampService campService, CampNpcMenusService campNpcMenus, Lang lang) {
        this.plugin = plugin;
        this.game = game;
        this.legacyStructures = legacyStructures;
        this.research = research;
        this.townClaims = townClaims;
        this.memberships = memberships;
        this.economy = economy;
        this.campService = campService;
        this.campNpcMenus = campNpcMenus;
        this.lang = lang;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        String primary = command.getName().toLowerCase(Locale.ROOT);
        String alias = label.toLowerCase(Locale.ROOT);
        String[] effectiveArgs = args;

        if (List.of("camp", "c", "civ", "town", "t", "build", "b", "research", "tech", "civadmin", "ad").contains(alias)) {
            String category = switch (alias) {
                case "c" -> "camp";
                case "t" -> "town";
                case "b" -> "build";
                case "tech" -> "research";
                default -> primary;
            };
            effectiveArgs = new String[args.length + 1];
            effectiveArgs[0] = category;
            System.arraycopy(args, 0, effectiveArgs, 1, args.length);
        }

        if (effectiveArgs.length == 0) {
            help(sender, primary);
            return true;
        }

        try {
            switch (effectiveArgs[0].toLowerCase(Locale.ROOT)) {
                case "camp" -> {
                    Player player = requirePlayer(sender);
                    handleCamp(player, effectiveArgs);
                    return true;
                }
                case "civ" -> {
                    Player player = requirePlayer(sender);
                    handleCiv(player, effectiveArgs);
                    return true;
                }
                case "town" -> {
                    Player player = requirePlayer(sender);
                    handleTown(player, effectiveArgs);
                    return true;
                }
                case "build" -> {
                    Player player = requirePlayer(sender);
                    handleBuild(player, effectiveArgs);
                    return true;
                }
                case "research" -> {
                    Player player = requirePlayer(sender);
                    handleResearch(player, effectiveArgs);
                    return true;
                }
                case "civadmin" -> {
                    handleCivAdmin(sender, effectiveArgs);
                    return true;
                }
                default -> {
                    help(sender, primary);
                    return true;
                }
            }
        } catch (IllegalArgumentException exception) {
            error(sender, exception.getMessage());
        } catch (SQLException exception) {
            plugin.getLogger().warning("CivCraft command failed: " + exception.getMessage());
            error(sender, msg("commands.error.database", "Ошибка базы данных: {error}", "error", exception.getMessage()));
        } catch (IOException exception) {
            plugin.getLogger().warning("CivCraft legacy build failed: " + exception.getMessage());
            error(sender, msg("commands.error.template", "Ошибка legacy-шаблона: {error}", "error", exception.getMessage()));
        }
        return true;
    }

    private void handleCamp(Player player, String[] args) throws SQLException {
        campService.handleCommand(player, args);
    }

    private void handleCiv(Player player, String[] args) throws SQLException {
        if(args.length>=2 && args[1].equalsIgnoreCase("set")){plugin.taxes().settings(player,args,true);return;}
        if(args.length>=2 && args[1].equalsIgnoreCase("upkeep")){plugin.upkeep().command(player,args,true);return;}
        if(args.length>=2 && (args[1].equalsIgnoreCase("gov") || args[1].equalsIgnoreCase("government"))) {
            plugin.governments().command(player,args);return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("help")) {
            for (String line : lang.list("commands.help.civ", List.of(
                    "&6Цивилизация: /civ info, members, invite <ник>, join, leave",
                    "&e/civ kick <ник> &7- исключить жителя (лидер)",
                    "&e/civ setleader <ник> &7- передать руководство",
                    "&e/civ deposit <сумма> / withdraw <сумма> &7- казна"))) {
                send(player, line);
            }
            send(player,"&e/civ upkeep / upkeep pay [сумма] &7- содержание и погашение долга");
            send(player,"&e/civ set taxes [процент] &7- налог цивилизации с городского дохода");
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("invite")) {
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null || !target.isOnline()) {
                throw new IllegalArgumentException("Для приглашения игрок должен быть онлайн.");
            }
            String civName = memberships.inviteCivilization(player, target);
            success(player, "Приглашение отправлено игроку " + target.getName() + " в цивилизацию " + civName);
            send(target, "&aВас пригласили в цивилизацию " + civName
                    + ". Напишите &e/civ join&a в течение 24 часов.");
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("join")) {
            success(player, "Вы вступили в цивилизацию " + memberships.joinCivilization(player));
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("leave")) {
            memberships.leaveCivilization(player);
            success(player, "Вы покинули цивилизацию.");
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("setleader")) {
            memberships.transferCivilizationLeadership(player, args[2]);
            success(player, "Новый лидер цивилизации: " + args[2]);
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("kick")) {
            memberships.kickCivilization(player, args[2]);
            success(player, "Игрок " + args[2] + " исключён из цивилизации.");
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("members")) {
            send(player, "&6Жители цивилизации: &e"
                    + String.join(", ", memberships.civilizationMembers(player)));
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("create")) {
            CivRecord civ = game.createCivilization(player, joinName(args, 2));
            success(player, msg("commands.civ.created", "Цивилизация {name} создана. Правительство: {government}", "name", civ.name(), "government", plugin.governments().name(civ.government())));
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("deposit")) {
            CivRecord civ = game.depositCiv(player, parseAmount(args[2]));
            success(player, msg("commands.civ.balance", "Баланс цивилизации: {coins}.", "coins", civ.coins()));
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("withdraw")) {
            CivRecord civ = game.withdrawCiv(player, parseAmount(args[2]));
            success(player, msg("commands.civ.balance", "Баланс цивилизации: {coins}.", "coins", civ.coins()));
            return;
        }
        ResidentProfile resident = game.resident(player);
        game.civ(resident).ifPresentOrElse(
                civ -> send(player, msg("commands.civ.info", "&6Цивилизация: &e{name} &7government={government}, coins={coins}", "name", civ.name(), "government", plugin.governments().name(civ.government()), "coins", civ.coins())),
                () -> error(player, msg("commands.civ.none", "Вы не состоите в цивилизации. Используйте /civ create <название>."))
        );
    }

    private void handleTown(Player player, String[] args) throws SQLException {
        if(args.length>=3 && args[1].equalsIgnoreCase("info") && args[2].equalsIgnoreCase("culture")){plugin.culture().info(player);return;}
        if(args.length>=2 && args[1].equalsIgnoreCase("set")){plugin.taxes().settings(player,args,false);return;}
        if(args.length>=2 && args[1].equalsIgnoreCase("upkeep")){plugin.upkeep().command(player,args,false);return;}
        if(args.length>=2 && args[1].equalsIgnoreCase("upgrade")){plugin.townUpgrades().command(player,args);return;}
        if (args.length >= 2 && args[1].equalsIgnoreCase("help")) {
            for (String line : lang.list("commands.help.town", List.of(
                    "&6Город: /town info, members, invite <ник>, join, leave",
                    "&e/town kick <ник> &7- исключить жителя (мэр)",
                    "&e/town setmayor <ник> &7- назначить нового мэра",
                    "&e/town claim / unclaim / claims &7- управление территорией"))) {
                send(player, line);
            }
            send(player,"&e/town upkeep / upkeep pay [сумма] &7- содержание и погашение долга");
            send(player,"&e/town set flattax [монеты], set taxrate [процент] &7- налоги жителей");
            send(player,"&e/res taxes, /res paydebt &7- ваш налоговый долг и его погашение");
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("invite")) {
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null || !target.isOnline()) {
                throw new IllegalArgumentException("Для приглашения игрок должен быть онлайн.");
            }
            String townName = memberships.inviteTown(player, target);
            success(player, "Приглашение отправлено игроку " + target.getName() + " в город " + townName);
            send(target, "&aВас пригласили в город " + townName
                    + ". Напишите &e/town join&a в течение 24 часов.");
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("join")) {
            success(player, "Вы вступили в город " + memberships.joinTown(player));
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("leave")) {
            memberships.leaveTown(player);
            success(player, "Вы покинули город. Гражданство цивилизации сохранено.");
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("setmayor")) {
            memberships.transferTownMayorship(player, args[2]);
            success(player, "Новый мэр города: " + args[2]);
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("kick")) {
            memberships.kickTown(player, args[2]);
            success(player, "Игрок " + args[2] + " исключён из города.");
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("members")) {
            send(player, "&6Жители города: &e"
                    + String.join(", ", memberships.townMembers(player)));
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("create")) {
            TownRecord town = game.createTown(player, joinName(args, 2));
            success(player, msg("commands.town.created", "Город {name} основан. Мэр: {mayor}, молотки: {hammers}/час, колбы: {beakers}/час, счастье: {happiness} ({state}).", "name", town.name(), "mayor", player.getName(), "hammers", formatNumber(town.hammersPerHour()), "beakers", formatNumber(town.beakersPerHour()), "happiness", town.happiness(), "state", town.happinessState()));
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("deposit")) {
            TownRecord town = game.depositTown(player, parseAmount(args[2]));
            success(player, msg("commands.town.balance", "Баланс города: {coins}.", "coins", town.coins()));
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("withdraw")) {
            TownRecord town = game.withdrawTown(player, parseAmount(args[2]));
            success(player, msg("commands.town.balance", "Баланс города: {coins}.", "coins", town.coins()));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("claim")) {
            TownClaimRecord claim = townClaims.claim(player);
            success(player, msg("commands.town.claimed", "Чанк {chunk_x},{chunk_z} заклаймлен городом #{town_id}.", "chunk_x", claim.chunkX(), "chunk_z", claim.chunkZ(), "town_id", claim.townId()));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("unclaim")) {
            townClaims.unclaim(player);
            success(player, msg("commands.town.unclaimed", "Текущий чанк отклаймлен."));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("claims")) {
            int count = townClaims.claimCount(player);
            send(player, msg("commands.town.claims", "&6Клаймы города: &e{count}/{max}", "count", count, "max", plugin.settings().maxTownClaims()));
            return;
        }
        ResidentProfile resident = game.resident(player);
        game.town(resident).ifPresentOrElse(
                town -> send(player, msg("commands.town.info", "&6Город: &e{name} &7уровень={level}, казна={coins}, молотки={hammers}/час, колбы={beakers}/час, доход={money}/час, счастье={happiness}, недовольство={unhappiness}, доля={percentage}% ({state}, ×{multiplier})", "name", town.name(), "level", town.level(), "coins", town.coins(), "hammers", formatNumber(town.hammersPerHour()), "beakers", formatNumber(town.beakersPerHour()), "money", formatNumber(town.moneyPerHour()), "happiness", formatNumber(town.happiness()), "unhappiness",formatNumber(town.unhappiness()),"percentage",formatNumber(town.happinessPercentage()*100),"state", town.happinessState(), "multiplier", formatNumber(town.productionMultiplier()))),
                () -> error(player, msg("commands.town.none", "У вас нет города. Используйте /town create <название>."))
        );
    }

    private void handleBuild(Player player, String[] args) throws IOException, SQLException {
        if (args.length >= 2 && args[1].equalsIgnoreCase("cancel")) {
            LegacyStructureService.CancelBuildResult result = legacyStructures.cancelActiveBuild(player);
            success(player, msg("commands.build.cancelled", "Стройка #{id} отменена. В казну города возвращено {refund} монет.", "id", result.build().id(), "refund", result.refund()));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("demolish")) {
            LegacyStructureService.DemolishBuildResult result = legacyStructures.demolishStructureAt(player);
            success(player, msg("commands.build.demolished", "Постройка #{id} разрушена. В казну города возвращено {refund} монет.", "id", result.build().id(), "refund", result.refund()));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("info")) {
            legacyStructures.structureInfoAt(player.getLocation()).ifPresentOrElse(
                    info -> sendStructureInfo(player, info),
                    () -> error(player, msg("commands.build.no-structure", "В этом чанке нет постройки."))
            );
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("time")) {
            legacyStructures.activeBuildInfo(player).ifPresentOrElse(
                    info -> sendStructureTime(player, info),
                    () -> error(player, msg("commands.build.no-active", "В вашем городе нет активной стройки."))
            );
            return;
        }
        if (args.length >= 2 && (args[1].equalsIgnoreCase("list") || args[1].equalsIgnoreCase("wonders"))) {
            boolean wonders=args[1].equalsIgnoreCase("wonders");
            send(player,wonders?"&6Чудеса света:":"&6Постройки:");
            for(var definition:legacyStructures.listDefinitions(Integer.MAX_VALUE)) {
                if(wonders && !definition.wonder())continue;
                send(player,"&e"+definition.id()+" &7— "+definition.displayName()
                        + ", стоимость: "+definition.cost()+", молотки: "+definition.hammerCost());
            }
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("legacy")) {
            throw new IllegalArgumentException(msg("commands.build.legacy-moved", "Legacy .def сборка перенесена в /civadmin build <file>."));
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("structure")) {
            String structureId = args[2];
            int y = args.length >= 4 ? parseBuildY(player, args[3]) : player.getLocation().getBlockY();
            String rotation = args.length >= 5 ? parseRotation(args[4]) : "0";
            legacyStructures.previewStructure(player, structureId, y, rotation);
            success(player, msg("commands.build.preview", "Фантомное превью постройки показано только вам. Напишите yes, чтобы начать строительство, или no, чтобы отменить."));
            return;
        }
        if (args.length >= 2) {
            if (args.length > 4) {
                throw new IllegalArgumentException(msg("commands.build.usage", "Использование: /build <постройка> [y] [0|90|180|270]"));
            }
            String structureId = args[1];
            int y = args.length >= 3 ? parseBuildY(player, args[2]) : player.getLocation().getBlockY();
            String rotation = args.length >= 4 ? parseRotation(args[3]) : "0";
            legacyStructures.previewStructure(player, structureId, y, rotation);
            success(player, msg("commands.build.preview", "Фантомное превью постройки показано только вам. Напишите yes, чтобы начать строительство, или no, чтобы отменить."));
            return;
        }
        helpBuild(player);
    }

    private void handleCivAdmin(CommandSender sender, String[] args) throws IOException, SQLException {
        if (!sender.hasPermission("civcraft.admin")) {
            throw new IllegalArgumentException(msg("commands.admin.op-only", "Эта команда доступна только администратору."));
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("item")) {
            if (args.length < 5 || !args[2].equalsIgnoreCase("give")) {
                send(sender, "&e/civadmin item give <ник> <ID предмета> [количество]");
                return;
            }
            Player target = Bukkit.getPlayerExact(args[3]);
            if (target == null) throw new IllegalArgumentException("Игрок должен быть онлайн.");
            int amount = args.length >= 6 ? Integer.parseInt(args[5]) : 1;
            ItemStack stack = plugin.nativeItems().create(args[4], amount);
            java.util.Map<Integer, ItemStack> overflow = target.getInventory().addItem(stack);
            for (ItemStack item : overflow.values()) target.getWorld().dropItemNaturally(target.getLocation(), item);
            success(sender, "Выдан предмет " + args[4] + " игроку " + target.getName());
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("recovery")) {
            if (args.length >= 3 && !args[2].equalsIgnoreCase("status")) {
                throw new IllegalArgumentException("Использование: /civadmin recovery status");
            }
            List<StorageBootstrap.InterruptedBuild> interrupted = legacyStructures.recoveryStatus();
            send(sender, "&6CivCraft: незавершённых или восстанавливаемых зданий: &e"
                    + interrupted.size());
            for (int i = 0; i < Math.min(15, interrupted.size()); i++) {
                StorageBootstrap.InterruptedBuild build = interrupted.get(i);
                send(sender, "&e#" + build.id() + " &7" + build.status() + "  "
                        + build.world() + " " + build.x() + "," + build.y() + "," + build.z()
                        + "  snapshot=" + (build.snapshotReady() ? "yes" : "no")
                        + "  journal=" + (build.journalStarted() ? "yes" : "legacy"));
            }
            if (interrupted.size() > 15) {
                send(sender, "&7Показаны первые 15 записей. Остальные доступны в журнале сервера и SQLite.");
            }
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("reload")) {
            plugin.reloadModernConfig();
            economy.reload(plugin.settings());
            game.updateSettings(plugin.settings());
            legacyStructures.updateSettings(plugin.settings());
            research.updateSettings(plugin.settings());
            townClaims.updateSettings(plugin.settings());
            plugin.reloadIntegrations();
            // reloadModernConfig() already refreshed campNpcMenus.
            success(sender, msg("commands.admin.reload", "Конфигурация перезагружена."));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("funds")) {
            if (args.length != 5 || !args[2].equalsIgnoreCase("give")) {
                send(sender, "&eИспользование: /civadmin funds give <ник> <сумма>");
                return;
            }
            Player recipient = Bukkit.getPlayerExact(args[3]);
            if (recipient == null || !recipient.isOnline()) {
                throw new IllegalArgumentException("Игрок должен быть на сервере: " + args[3]);
            }
            long amount;
            try {
                amount = Long.parseLong(args[4]);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Сумма должна быть целым числом.");
            }
            if (amount < 1L || amount > 10_000_000L) {
                throw new IllegalArgumentException("Разрешена сумма от 1 до 10 000 000.");
            }
            // Only civcraft.admin can reach this branch. Support both local
            // SQLite balances and an installed Vault economy provider.
            economy.deposit(recipient, amount, "administrator grant");
            success(sender, "Начислено игроку " + recipient.getName() + ": "
                    + amount + " монет. Новый баланс: " + Math.round(economy.balance(recipient)));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("foundation")) {
            if (args.length < 5 || !args[2].equalsIgnoreCase("give")) {
                send(sender, "&eИспользование: /civadmin foundation give <ник> <camp|civilization|town> [количество]");
                return;
            }
            Player recipient = Bukkit.getPlayerExact(args[3]);
            if (recipient == null || !recipient.isOnline()) {
                throw new IllegalArgumentException("Игрок должен быть на сервере: " + args[3]);
            }
            FoundationItemFactory.Kind kind = FoundationItemFactory.Kind.parse(args[4]);
            int quantity = 1;
            if (args.length >= 6) {
                try {
                    quantity = Integer.parseInt(args[5]);
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("Количество должно быть целым числом от 1 до 64.");
                }
            }
            ItemStack foundation = FoundationItemFactory.create(plugin, kind, quantity);
            HashMap<Integer, ItemStack> overflow = recipient.getInventory().addItem(foundation);
            if (!overflow.isEmpty()) {
                // Do not drop privileged foundation items on the ground when
                // inventory is full; tell the admin so they can retry.
                int undelivered = overflow.values().stream().mapToInt(ItemStack::getAmount).sum();
                success(sender, "Выдано игроку " + recipient.getName() + ": "
                        + (quantity - undelivered) + " из " + quantity + ". Мест в инвентаре не хватило.");
            } else {
                success(sender, "Выдано игроку " + recipient.getName() + ": " + kind.id() + " x" + quantity);
            }
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("integrations")) {
            send(sender, msg("commands.admin.integrations-header", "&eИнтеграции CivCraft:"));
            plugin.integrations().statusLines().forEach(line -> sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(line)));
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("npc") && args[2].equalsIgnoreCase("reload")) {
            if (campNpcMenus != null) {
                campNpcMenus.reloadEverything();
            }
            success(sender, msg("commands.admin.npc-reload", "Camp NPC menus перезагружены."));
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("build")) {
            Player player = requirePlayer(sender);
            int y = args.length >= 4 ? parseBuildY(player, args[3]) : player.getLocation().getBlockY();
            String rotation = args.length >= 5 ? parseRotation(args[4]) : "0";
            QueuedLegacyBuild build = legacyStructures.buildAdminLegacy(player, args[2], y, rotation);
            success(player, msg("commands.admin.build-started", "Legacy .def постройка #{id} запущена: {blocks} блоков, размер {size_x}x{size_y}x{size_z}", "id", build.id(), "blocks", build.queuedBlocks(), "size_x", build.sizeX(), "size_y", build.sizeY(), "size_z", build.sizeZ()));
            return;
        }
        helpAdmin(sender);
    }

    private void handleResearch(Player player, String[] args) throws SQLException {
        if (args.length >= 2 && args[1].equalsIgnoreCase("rate")) {
            ResidentProfile resident = game.resident(player);
            if (resident.civId() == null) {
                throw new IllegalArgumentException("Для исследований требуется цивилизация.");
            }
            double perHour = research.effectiveSciencePerHour(resident.civId());
            send(player, "&6Производство науки цивилизации: &e" + formatNumber(perHour)
                    + " колбочек/час &7(" + formatNumber(perHour / 60.0) + " в минуту)");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
            List<String> techs = research.listTechs(25).stream()
                    .map(tech -> tech.id() + "(" + tech.name() + ", beakers=" + tech.beakerCost() + ", coins=" + tech.coinCost() + ")")
                    .toList();
            send(player, msg("commands.research.list", "&6Технологии: &e{list}", "list", String.join(", ", techs)));
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("start")) {
            TechDefinition definition = research.startResearch(player, args[2]);
            success(player, msg("commands.research.started", "Исследование {name} начато. Нужно beakers: {beakers}", "name", definition.name(), "beakers", definition.beakerCost()));
            return;
        }
        ResidentProfile resident = game.resident(player);
        research.progress(resident).ifPresentOrElse(
                progress -> send(player, msg("commands.research.progress", "&6Исследование: &e{tech} &7{progress}/{required} ({percent}%)", "tech", progress.techId(), "progress", Math.round(progress.progress()), "required", Math.round(progress.requiredBeakers()), "percent", Math.round(progress.percent()))),
                () -> error(player, msg("commands.research.none", "Активного исследования нет. Используйте /research list и /research start <id>."))
        );
    }

    private void sendStructureInfo(Player player, StorageBootstrap.StructureBuildView info) {
        send(player, msg("commands.build.info.line1", "&6Постройка: &e{name} &7#{id}", "name", info.displayName(), "id", info.id()));
        send(player, msg("commands.build.info.line2", "&7Город: {town}, status={status}, cost={cost}, chunk={chunk_x},{chunk_z}", "town", info.townName() == null ? "-" : info.townName(), "status", info.status(), "cost", info.cost(), "chunk_x", info.chunkX(), "chunk_z", info.chunkZ()));
        send(player, msg("commands.build.info.line3", "&7Origin: {world} {x} {y} {z}, hammers={hammers}, production={production}/hour", "world", info.world(), "x", info.x(), "y", info.y(), "z", info.z(), "hammers", formatNumber(info.totalHammers()), "production", formatNumber(info.hammersPerHour())));
    }

    private void sendStructureTime(Player player, StorageBootstrap.StructureBuildView info) {
        long now = System.currentTimeMillis();
        send(player, msg("commands.build.time.line1", "&6Стройка: &e{name} &7#{id}", "name", info.displayName(), "id", info.id()));
        send(player, msg("commands.build.time.line2", "&7Готово: {percent}% | осталось: {remaining}", "percent", String.format(Locale.ROOT, "%.1f", info.progressPercent(now)), "remaining", formatDuration(info.remainingMillis(now))));
    }

    private void helpBuild(Player player) {
        for (String line : lang.list("commands.help.build", List.of(
                "&e/b <id> [y] [0|90|180|270]&7 - превью постройки",
                "&e/b cancel&7 - отменить активную стройку города и вернуть 90% стоимости",
                "&e/b demolish&7 - разрушить постройку в текущем чанке и вернуть 50% стоимости в казну",
                "&e/b info&7 - инфа о постройке в текущем чанке",
                "&e/b time&7 - прогресс активной стройки города"))) {
            send(player, line);
        }
    }

    private void helpAdmin(CommandSender sender) {
        for (String line : lang.list("commands.help.admin", List.of(
                "&e/civadmin reload&7 - перезагрузить конфиги",
                "&e/civadmin integrations&7 - статус интеграций",
                "&e/civadmin recovery status&7 - незавершённые стройки и восстановление",
                "&e/civadmin npc reload&7 - перезагрузить NPC/GUI лагерей",
                "&e/civadmin foundation give <игрок> <camp|civilization|town> [1-64]&7 - выдать основание",
                "&e/civadmin funds give <игрок> <сумма>&7 - выдать монеты для настройки экономики",
                "&e/civadmin build <file> [y] [0|90|180|270]&7 - legacy .def сборка"))) {
            send(sender, line);
        }
    }

    private Player requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            throw new IllegalArgumentException(msg("commands.error.player-only", "Эта команда доступна только игроку."));
        }
        return player;
    }

    private String joinName(String[] args, int start) {
        return String.join(" ", java.util.Arrays.copyOfRange(args, start, args.length)).trim();
    }

    private long parseAmount(String input) {
        try {
            if (input.contains(".") || input.contains(",")) {
                throw new NumberFormatException(input);
            }
            long amount = Long.parseLong(input);
            if (amount <= 0) {
                throw new NumberFormatException(input);
            }
            return amount;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(msg("commands.error.positive-int", "Сумма должна быть положительным целым числом."));
        }
    }

    private int parseBuildY(Player player, String input) {
        try {
            int y = Integer.parseInt(input);
            if (y < player.getWorld().getMinHeight() || y >= player.getWorld().getMaxHeight()) {
                throw new NumberFormatException(input);
            }
            return y;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(msg("commands.error.y", "Y должен быть целым числом в пределах высоты мира."));
        }
    }

    private String parseRotation(String input) {
        if (List.of("0", "90", "180", "270").contains(input)) {
            return input;
        }
        throw new IllegalArgumentException(msg("commands.error.rotation", "Поворот должен быть только 0, 90, 180 или 270."));
    }

    private String formatNumber(double value) {
        if (value == Math.rint(value)) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String formatDuration(long millis) {
        if (millis <= 0L) {
            return msg("commands.common.now", "сразу");
        }
        long totalSeconds = Math.max(1L, (long) Math.ceil(millis / 1000.0));
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return hours + "ч " + minutes + "м";
        }
        if (minutes > 0L) {
            return minutes + "м " + seconds + "с";
        }
        return seconds + "с";
    }

    private void help(CommandSender sender, String commandName) {
        String key = commandName.equalsIgnoreCase("civadmin") ? "commands.help.admin" : "commands.help.player";
        List<String> fallback = commandName.equalsIgnoreCase("civadmin")
                ? List.of("&6CivCraft admin", "&e/civadmin reload&7 - перезагрузить конфиги", "&e/civadmin integrations&7 - статус интеграций", "&e/civadmin npc reload&7 - перезагрузить NPC/GUI лагерей", "&e/civadmin build <file>&7 - legacy .def сборка")
                : List.of("&6CivCraft", "&e/camp&7 - команды лагеря", "&e/build&7 - постройки", "&e/research&7 - технологии", "&e/civ&7 - цивилизация", "&e/town&7 - город");
        for (String line : lang.list(key, fallback)) {
            send(sender, line);
        }
    }

    private void success(CommandSender sender, String message) {
        send(sender, "§a" + stripOuterColor(message));
    }

    private void error(CommandSender sender, String message) {
        send(sender, "§c" + stripOuterColor(message));
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(component(message));
    }

    private Component component(String message) {
        return legacy.deserialize(colorize(message == null ? "" : message));
    }

    private String msg(String key, String fallback, Object... replacements) {
        return lang.msg(key, fallback, replacements);
    }

    private String colorize(String message) {
        return message == null ? "" : message.replace('&', '§');
    }

    private String stripOuterColor(String message) {
        if (message == null) {
            return "";
        }
        String colored = colorize(message);
        if (colored.length() >= 2 && colored.charAt(0) == '§') {
            return colored.substring(2);
        }
        return colored;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        String primary = alias.toLowerCase(Locale.ROOT);
        if(primary.equals("ad"))primary="civadmin";
        if(primary.equals("civ") && args.length==2 && args[0].equalsIgnoreCase("set"))return List.of("taxes");
        if(List.of("town","t").contains(primary) && args.length==2 && args[0].equalsIgnoreCase("set"))return List.of("flattax","taxrate");
        if(List.of("civ","town","t").contains(primary) && args.length==2 && args[0].equalsIgnoreCase("upkeep"))return List.of("pay");
        if(List.of("town","t").contains(primary) && args.length>=2 && args[0].equalsIgnoreCase("upgrade")) {
            if(args.length==2)return List.of("list","purchased","buy");
            if(args.length==3 && args[1].equalsIgnoreCase("buy"))return plugin.townUpgrades().ids();
        }
        if(primary.equals("civ") && args.length>=2 && (args[0].equalsIgnoreCase("gov") || args[0].equalsIgnoreCase("government"))) {
            if(args.length==2)return List.of("info","list","change");
            if(args.length==3 && args[1].equalsIgnoreCase("change"))return plugin.governments().ids();
        }
        if (List.of("build", "b").contains(primary) && args.length == 1) {
            return List.of("list", "cancel", "demolish", "info", "time", "s_townhall", "s_capitol");
        }
        if (List.of("research", "tech").contains(primary) && args.length == 1) {
            return List.of("list", "start", "status", "rate");
        }
        if (List.of("camp", "c").contains(primary) && args.length == 1) {
            return campService.completions(1);
        }
        if (primary.equals("civ") && args.length == 1) {
            return List.of("help", "create", "info", "gov", "set", "upkeep", "members", "invite", "join", "leave", "kick", "setleader", "deposit", "withdraw");
        }
        if (List.of("town", "t").contains(primary) && args.length == 1) {
            return List.of("help", "create", "info", "upgrade", "set", "upkeep", "members", "invite", "join", "leave", "kick", "setmayor", "claim", "unclaim", "claims", "deposit", "withdraw");
        }
        if (primary.equals("civadmin")) {
            if (!sender.hasPermission("civcraft.admin")) {
                return List.of();
            }
            if (args.length == 1) {
                return List.of("reload", "integrations", "recovery", "npc", "build", "foundation", "funds");
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("recovery")) {
                return List.of("status");
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("npc")) {
                return List.of("reload");
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("foundation")) {
                return List.of("give");
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("funds")) {
                return List.of("give");
            }
            if (args.length == 4 && args[0].equalsIgnoreCase("foundation") && args[1].equalsIgnoreCase("give")) {
                return List.of("camp", "civilization", "town");
            }
        }
        return List.of();
    }
}
