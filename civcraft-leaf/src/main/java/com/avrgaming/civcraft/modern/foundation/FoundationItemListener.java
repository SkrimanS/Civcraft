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
    private final double settlerFriendlyDistance,settlerEnemyDistance,settlerLandDistance;
    private ModernCivCraftSettings settings;
    private final Map<UUID, PendingFoundation> pending = new ConcurrentHashMap<>();
    private long lastRecoveryWarning;
    public boolean hasPending(UUID player){
        if(pending.containsKey(player))return true;
        if(plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin civ)try{return civ.storage().query(db->FoundationEscrowStore.activePlayer(db,player));}
        catch(SQLException failure){warnRecovery(failure);return true;}
        return false;
    }

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
        var towns=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("civcraft/data/town.yml").toFile());
        var civs=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("civcraft/data/civ.yml").toFile());
        settlerFriendlyDistance=towns.getDouble("town.min_town_distance",settings.minTownDistance());
        settlerEnemyDistance=towns.getDouble("town.min_town_distance_enemy",settlerFriendlyDistance);
        settlerLandDistance=civs.getDouble("civ.min_distance",15);
        if(!Double.isFinite(settlerFriendlyDistance) || settlerFriendlyDistance<0 || !Double.isFinite(settlerEnemyDistance) || settlerEnemyDistance<0 || !Double.isFinite(settlerLandDistance) || settlerLandDistance<0)throw new IllegalArgumentException("Неверные расстояния основания города поселенцем.");
        if(plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin civ)try{civ.storage().transaction(db->{FoundationEscrowStore.install(db);FoundationCreationProtection.install(db);return null;});}
        catch(SQLException failure){throw new IllegalStateException("Cannot initialize foundation escrow",failure);}
        recoverEscrows();
        plugin.getServer().getScheduler().runTaskTimer(plugin,()->{expireTownRequests();recoverEscrows();},20L,20L);
    }

    public void updateSettings(ModernCivCraftSettings settings) {
        this.settings = settings;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() == null || (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR)) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir())return;
        boolean settler="u_settler".equals(item.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(plugin,"material-id"),PersistentDataType.STRING));
        // The original custom-item dispatcher also handles a settler's air click,
        // which Bukkit can initially mark cancelled when vanilla has no action.
        if(!settler && (event.isCancelled() || event.getHand()!=EquipmentSlot.HAND || event.getAction()!=Action.RIGHT_CLICK_BLOCK))return;
        FoundationKind kind=kind(item);if(kind==null)return;
        if(event.getClickedBlock()!=null && plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin civ
                && civ.trades()!=null && civ.trades().isPublicTradePoint(event.getClickedBlock()))return;
        Player player = event.getPlayer();
        if(plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin civ && civ.isWarCampPending(player.getUniqueId())) {
            player.sendMessage(net.kyori.adventure.text.Component.text("Сначала подтвердите военный лагерь словом yes или отмените другим ответом."));
            event.setCancelled(true);
            return;
        }
        if (hasPending(player.getUniqueId())) {
            PendingFoundation current = pending.get(player.getUniqueId());
            if(current==null){player.sendMessage(net.kyori.adventure.text.Component.text("Предыдущее основание ожидает восстановления. Новое основание пока недоступно."));return;}
            player.sendMessage(component(current != null && current.awaitingPreview
                    ? "foundation.pending-preview"
                    : "foundation.pending-input",
                    current != null && current.awaitingPreview
                            ? "&eСначала напишите yes или no для превью."
                            : "&eСначала завершите текущее основание. Введите данные в чат."));
            event.setCancelled(true);
            return;
        }
        Block clicked = event.getClickedBlock();
        if (!settler && clicked == null) {
            return;
        }
        Location placeLocation = settler?player.getLocation().clone():clicked.getRelative(event.getBlockFace() == null ? BlockFace.UP : event.getBlockFace()).getLocation();
        event.setCancelled(true);
        if(plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin civ) {
            try {
                civ.goodieTransfers().requireUnlocked(player.getUniqueId());civ.trades().recover(player);
                var held=player.getInventory().getItem(event.getHand());
                if(held==null || !held.equals(item))throw new IllegalArgumentException("Предмет основания изменился при восстановлении инвентаря. Повторите действие.");
            }catch(IllegalArgumentException waiting){player.sendMessage(net.kyori.adventure.text.Component.text(waiting.getMessage()));return;}
            catch(SQLException|RuntimeException failure){plugin.getLogger().warning("Foundation inventory check failed: "+failure.getMessage());player.sendMessage(net.kyori.adventure.text.Component.text("Не удалось проверить инвентарь основания. Повторите позже."));return;}
        }

        if (kind==FoundationKind.CAMP) {
            event.setCancelled(true);
            var foundation=reserveFoundation(player,event.getHand(),FoundationKind.CAMP,placeLocation);if(foundation==null)return;
            pending.put(player.getUniqueId(),foundation);
            player.sendMessage(component("foundation.enter-camp-name", "&eВведите название лагеря. От 5 до 16 символов."));
            return;
        }
        if (kind==FoundationKind.CIVILIZATION) {
            event.setCancelled(true);
            var foundation=reserveFoundation(player,event.getHand(),FoundationKind.CIVILIZATION,placeLocation);if(foundation==null)return;
            pending.put(player.getUniqueId(),foundation);
            player.sendMessage(component("foundation.enter-civ-name", "&eВведите название цивилизации. От 5 до 16 символов."));
            return;
        }
        if (kind==FoundationKind.TOWN) {
            event.setCancelled(true);
            Long owningCiv=item.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(plugin,"unit-civ"),PersistentDataType.LONG);
            if(settler && owningCiv==null){player.sendMessage(net.kyori.adventure.text.Component.text("У поселенца не указана цивилизация основания."));return;}
            if(owningCiv!=null) {
                try{var resident=game.resident(player);if(resident.townId()==null || resident.civId()==null || !owningCiv.equals(resident.civId())){player.sendMessage(net.kyori.adventure.text.Component.text("Этот поселенец может основать город только для жителей своей цивилизации."));return;}}
                catch(SQLException failure){plugin.getLogger().warning("Settler ownership check failed: "+failure.getMessage());player.sendMessage(net.kyori.adventure.text.Component.text("Не удалось проверить владельца поселенца. Повторите позже."));return;}
            }
            Long sourceTown=null;
            if(settler && plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin civ) {
                try{sourceTown=civ.storage().query(db->{long town=SettlerTownStore.sourceTown(db,player.getUniqueId(),owningCiv);SettlerTownStore.requireLocation(db,owningCiv,placeLocation,settlerFriendlyDistance,settlerEnemyDistance,settlerLandDistance,true,System.currentTimeMillis());return town;});}
                catch(IllegalArgumentException refused){player.sendMessage(net.kyori.adventure.text.Component.text(refused.getMessage()));return;}
                catch(SQLException failure){plugin.getLogger().warning("Settler membership check failed: "+failure.getMessage());player.sendMessage(net.kyori.adventure.text.Component.text("Не удалось проверить жителя для основания города."));return;}
            }
            var foundation=reserveFoundation(player,event.getHand(),FoundationKind.TOWN,placeLocation);if(foundation==null)return;
            foundation.nativeSettler=settler;foundation.sourceTown=sourceTown;foundation.sourceCiv=owningCiv;
            pending.put(player.getUniqueId(),foundation);
            player.sendMessage(component("foundation.enter-town-name", "&eВведите название города. От 5 до 16 символов."));
        }
    }

    private FoundationKind kind(ItemStack item) {
        if(FoundationItemFactory.matches(plugin,item,FoundationItemFactory.Kind.CAMP) || matches(item,settings.campFoundationMmoItemId(),settings.campFoundationMmoItemType()))return FoundationKind.CAMP;
        if(FoundationItemFactory.matches(plugin,item,FoundationItemFactory.Kind.CIVILIZATION) || matches(item,settings.civFlagMmoItemId(),settings.civFlagMmoItemType()))return FoundationKind.CIVILIZATION;
        if(FoundationItemFactory.matches(plugin,item,FoundationItemFactory.Kind.TOWN) || matches(item,settings.settlerMmoItemId(),settings.settlerMmoItemType()))return FoundationKind.TOWN;
        return null;
    }

    private PendingFoundation reserveFoundation(Player player,EquipmentSlot hand,FoundationKind kind,Location location) {
        var held=player.getInventory().getItem(hand);if(held==null || held.getType().isAir())return null;
        var consumed=held.clone();consumed.setAmount(1);
        var data=new PendingFoundation(kind,location,consumed);
        if(plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin civ) {
            data.escrow=UUID.randomUUID().toString();
            try{civ.goodieTransfers().reserveFoundation(player,hand,held.clone(),kind.name(),data.escrow);}
            catch(SQLException|RuntimeException failure){warnRecovery(failure);player.sendMessage(net.kyori.adventure.text.Component.text(failure instanceof IllegalArgumentException?failure.getMessage():"Не удалось завершить резерв основания. Записанная операция будет восстановлена сервером."));return null;}
        }else consumeOneHand(player,hand);
        return data;
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

    /**
     * Called before the plugin is disabled: return unconfirmed items while
     * their owners are still connected, rather than silently deleting them.
     */
    public void shutdown() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PendingFoundation data = pending.remove(player.getUniqueId());
            if (data != null && !data.success) {
                structures.cancelPreview(player, true);
                restoreConsumedItem(player, data);
            }
        }
        pending.clear();
    }

    private void handleInput(Player player, String input) {
        PendingFoundation data = pending.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        try {
            if(data.creationStarted){pending.remove(player.getUniqueId());player.sendMessage(net.kyori.adventure.text.Component.text("Создание поселения ожидает восстановления. Зарезервированный предмет сохранён сервером."));return;}
            if(input.equalsIgnoreCase("cancel")) {
                structures.cancelPreview(player,true);restoreAndRemovePending(player,data);
                player.sendMessage(component("foundation.cancelled","&eОснование отменено. Предмет будет возвращён после сохранения операции."));return;
            }
            if(data.escrow!=null && plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin civ && !civ.storage().query(db->FoundationEscrowStore.reservationSaved(db,data.escrow))) {
                player.sendMessage(net.kyori.adventure.text.Component.text("Предмет основания ещё сохраняется. Повторите ответ через секунду."));return;
            }
            if(data.awaitingApproval){player.sendMessage(net.kyori.adventure.text.Component.text("Ожидается согласие главы цивилизации. Для отмены напишите cancel."));return;}
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
            if (exception instanceof SQLException) {
                plugin.getLogger().warning("Foundation storage failed for " + player.getName() + ": " + exception.getMessage());
            }
            String reason = exception instanceof SQLException
                    ? "не удалось сохранить основание; подробности находятся в журнале сервера"
                    : exception.getMessage();
            player.sendMessage(component("foundation.error", "&cОшибка: {error}", "error", reason));
        } catch (Exception exception) {
            rollbackCreatedObjects(player, data);
            structures.cancelPreview(player, true);
            restoreAndRemovePending(player, data);
            plugin.getLogger().warning("Foundation item failed for " + player.getName() + ": " + exception.getMessage());
            player.sendMessage(component("foundation.create-error", "&cОшибка создания основания: {error}", "error", "не удалось завершить создание; подробности находятся в журнале сервера"));
        }
    }

    private void handlePreviewAnswer(Player player, PendingFoundation data, String input) throws Exception {
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        if (!data.nativeSettler && !normalized.equals("yes") && !normalized.equals("no")) {
            player.sendMessage(component("foundation.preview-confirm-again", "&eВведите yes, чтобы подтвердить основание, или no, чтобы отменить."));
            return;
        }
        if (!normalized.equals("yes")) {
            structures.cancelPreview(player, false);
            restoreAndRemovePending(player, data);
            player.sendMessage(component("foundation.cancelled", "&eОснование отменено. Предмет будет возвращён после сохранения операции."));
            return;
        }
        structures.cancelPreview(player, true);
        if(data.nativeSettler){requestTownApproval(player,data);return;}
        confirmFoundation(player, data);
    }

    private void requestTownApproval(Player player,PendingFoundation data)throws SQLException {
        var civ=(com.avrgaming.civcraft.modern.CivCraftModernPlugin)plugin;
        civ.storage().query(db->{
            if(SettlerTownStore.sourceTown(db,player.getUniqueId(),data.sourceCiv)!=data.sourceTown)throw new IllegalArgumentException("Вы сменили город во время основания.");
            try(var s=db.prepareStatement("SELECT 1 FROM active_structure_builds b JOIN towns t ON t.id=b.town_id WHERE t.civ_id=? AND b.structure_id='s_capitol' LIMIT 1")){s.setLong(1,data.sourceCiv);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("У цивилизации нет действующего капитолия для основания нового города.");}}
            return null;
        });
        for(var other:pending.values())if(other!=data && other.awaitingApproval && java.util.Objects.equals(other.sourceCiv,data.sourceCiv) && other.approvalExpires>System.currentTimeMillis())throw new IllegalArgumentException("В вашей цивилизации уже ожидается ответ на запрос основания.");
        data.awaitingPreview=false;data.awaitingApproval=true;data.approvalExpires=System.currentTimeMillis()+30000L;
        var owner=civ.storage().findCiv(data.sourceCiv).orElseThrow(()->new IllegalArgumentException("Цивилизация больше не существует."));
        var leader=plugin.getServer().getPlayer(owner.leaderUuid());
        if(leader!=null)leader.sendMessage(net.kyori.adventure.text.Component.text(player.getName()+" просит основать город «"+data.townName+"» в "+data.location.getWorld().getName()+": "+data.location.getBlockX()+", "+data.location.getBlockY()+", "+data.location.getBlockZ()+". Ответьте /accept или /deny в течение 30 секунд."));
        player.sendMessage(net.kyori.adventure.text.Component.text("Запрос основания отправлен главе цивилизации. Ожидание ответа — 30 секунд; отмена: cancel."));
    }

    public boolean respondTownRequest(org.bukkit.command.CommandSender sender,boolean accepted) {
        if(!(sender instanceof Player leader)){sender.sendMessage("Ответить на запрос может игрок — глава цивилизации.");return true;}
        try {
            var civ=(com.avrgaming.civcraft.modern.CivCraftModernPlugin)plugin;var resident=game.resident(leader);
            if(resident.civId()==null || !civ.storage().query(db->SettlerTownStore.authority(db,leader.getUniqueId(),resident.civId())))throw new IllegalArgumentException("Основание города одобряет глава цивилизации.");
            var entry=pending.entrySet().stream().filter(e->e.getValue().awaitingApproval && java.util.Objects.equals(e.getValue().sourceCiv,resident.civId()) && e.getValue().approvalExpires>System.currentTimeMillis()).findFirst().orElseThrow(()->new IllegalArgumentException("Нет ожидающего запроса основания города."));
            var founder=plugin.getServer().getPlayer(entry.getKey());var data=entry.getValue();
            if(founder==null)throw new IllegalArgumentException("Основатель вышел с сервера.");
            if(!accepted){restoreAndRemovePending(founder,data);founder.sendMessage(net.kyori.adventure.text.Component.text("Глава цивилизации отклонил основание города. Предмет будет возвращён после сохранения операции."));leader.sendMessage("Запрос основания отклонён.");return true;}
            data.awaitingApproval=false;
            try{confirmFoundation(founder,data);leader.sendMessage("Основание города одобрено.");}
            catch(Exception failure){rollbackCreatedObjects(founder,data);structures.cancelPreview(founder,true);restoreAndRemovePending(founder,data);plugin.getLogger().warning("Approved settler foundation failed: "+failure.getMessage());founder.sendMessage(net.kyori.adventure.text.Component.text(failure instanceof IllegalArgumentException?failure.getMessage():"Не удалось завершить основание города. Подробности находятся в журнале сервера."));leader.sendMessage("Основание не завершено; основатель получил причину отказа.");}
        }catch(IllegalArgumentException failure){leader.sendMessage(net.kyori.adventure.text.Component.text(failure.getMessage()));}
        catch(SQLException failure){plugin.getLogger().warning("Town approval failed: "+failure.getMessage());leader.sendMessage("Не удалось обработать запрос основания города.");}
        return true;
    }

    private void expireTownRequests() {
        long now=System.currentTimeMillis();
        for(var entry:pending.entrySet())if(entry.getValue().awaitingApproval && entry.getValue().approvalExpires<=now) {
            var player=plugin.getServer().getPlayer(entry.getKey());if(player==null)continue;
            restoreAndRemovePending(player,entry.getValue());player.sendMessage(net.kyori.adventure.text.Component.text("Глава цивилизации не ответил на запрос основания. Предмет будет возвращён после сохранения операции."));
        }
    }

    private void warnRecovery(Exception failure) {
        long now=System.currentTimeMillis();if(now-lastRecoveryWarning<30000)return;lastRecoveryWarning=now;
        plugin.getLogger().severe("Foundation escrow recovery retained: "+failure.getMessage());
    }
    private void recoverEscrows() {
        if(!(plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin civ))return;
        try {
            for(var escrow:civ.storage().query(FoundationEscrowStore::active)) {
              try {
                if(pending.values().stream().anyMatch(data->escrow.id().equals(data.escrow)))continue;
                if(escrow.state().equals("CREATING")) {
                    var plan=civ.storage().query(db->FoundationCreationStore.find(db,escrow.id()));
                    if(plan!=null && plan.ready() && plan.fee()==0) {
                        civ.storage().transaction(db->{FoundationCreationStore.commitReady(db,escrow.id(),false);return null;});
                    }else if(plan!=null && plan.fee()==0 && !plan.ready() && plan.build()==null) {
                        civ.storage().transaction(db->{FoundationCreationRollback.cancelIncomplete(db,escrow.id());return null;});
                    }else {warnRecovery(new IllegalStateException("Foundation creation awaits reconciliation: "+escrow.id()));continue;}
                }
                if(escrow.state().equals("RESERVING") || escrow.state().equals("RESERVED"))civ.storage().transaction(db->{FoundationEscrowStore.cancel(db,escrow.id(),false);return null;});
                var current=civ.storage().query(db->FoundationEscrowStore.find(db,escrow.id()));
                if(!current.state().equals("CANCEL_REQUESTED"))continue;
                var player=plugin.getServer().getPlayer(current.player());if(player==null || player.isDead())continue;
                try{civ.goodieTransfers().refundFoundation(player,current.id());}
                catch(IllegalArgumentException waiting){/* The reserve/other player journal must finish first. */}
              }catch(SQLException|RuntimeException failure){warnRecovery(failure);}
            }
        }catch(SQLException|RuntimeException failure){warnRecovery(failure);}
    }

    @FunctionalInterface private interface CreationWork<T>{T run()throws Exception;}
    private <T> T creationWrite(PendingFoundation data,CreationWork<T> work)throws Exception {
        data.creationWriteInFlight=true;
        T value=work.run();data.creationWriteInFlight=false;return value;
    }
    private void completeReservation(PendingFoundation data)throws SQLException {
        if(data.escrow!=null && plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin civ) {
            data.creationWriteInFlight=true;
            civ.storage().transaction(db->{FoundationCreationStore.commitReady(db,data.escrow,true);return null;});
            data.creationWriteInFlight=false;
        }
        data.success=true;
    }
    private void validateBeforeCreation(Player player,PendingFoundation data,Location origin)throws SQLException {
        var resident=game.resident(player);
        switch(data.kind) {
            case CAMP -> {
                if(resident.civId()!=null)throw new IllegalArgumentException("Игрок в цивилизации не может основать лагерь.");
                if(resident.campId()!=null)throw new IllegalArgumentException("У вас уже есть лагерь.");
            }
            case CIVILIZATION -> {
                if(resident.campId()!=null)throw new IllegalArgumentException("Игрок в лагере не может основать цивилизацию.");
                if(resident.civId()!=null)throw new IllegalArgumentException("Вы уже состоите в цивилизации.");
            }
            case TOWN -> {
                if(resident.civId()==null)throw new IllegalArgumentException("Сначала создайте или вступите в цивилизацию.");
                if(!data.nativeSettler && resident.townId()!=null)throw new IllegalArgumentException("У вас уже есть город.");
            }
        }
        if(plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin civ) {
            if(data.kind==FoundationKind.CAMP && civ.storage().findCampByOwner(player.getUniqueId()).isPresent())throw new IllegalArgumentException("У вас уже есть лагерь.");
            if(data.nativeSettler)civ.storage().query(db->{
                if(SettlerTownStore.sourceTown(db,player.getUniqueId(),data.sourceCiv)!=data.sourceTown)throw new IllegalArgumentException("Вы сменили город во время основания.");
                SettlerTownStore.requireLocation(db,data.sourceCiv,data.location,settlerFriendlyDistance,settlerEnemyDistance,settlerLandDistance,false,System.currentTimeMillis());return null;
            });
            if(data.kind==FoundationKind.TOWN && !data.nativeSettler) {
                if(civ.storage().hasTownNear(origin,settings.minTownDistance()))throw new IllegalArgumentException("Слишком близко к другому городу.");
                if(civ.economy().balance(player)<settings.townCost())throw new IllegalArgumentException("Недостаточно монет для административного основания города.");
            }
            String table=switch(data.kind){case CAMP->"camps";case TOWN->"towns";case CIVILIZATION->"civilizations";};
            String name=switch(data.kind){case CAMP->data.campName;case TOWN->data.townName;case CIVILIZATION->data.civName;};
            civ.storage().query(db->{try(var s=db.prepareStatement("SELECT 1 FROM "+table+" WHERE name=? COLLATE NOCASE")){s.setString(1,name);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Название поселения уже занято.");}}return null;});
            if(data.kind==FoundationKind.CIVILIZATION)civ.storage().query(db->{try(var s=db.prepareStatement("SELECT 1 FROM towns WHERE name=? COLLATE NOCASE")){s.setString(1,data.capitalName);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Название столицы уже занято.");}}return null;});
        }
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
        if(data.nativeSettler && plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin civ)civ.biomeSurvey().show(player);
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
        validateBeforeCreation(player,data,origin);
        if(data.escrow!=null && plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin civ) {
            civ.goodieTransfers().requireUnlocked(player.getUniqueId());
            var draft=new FoundationCreationStore.Draft(data.nativeSettler,
                    switch(data.kind){case CAMP->data.campName;case TOWN->data.townName;case CIVILIZATION->data.civName;},
                    data.kind==FoundationKind.CIVILIZATION?data.capitalName:data.kind==FoundationKind.TOWN?data.townName:"",
                    data.civTag,
                    switch(data.kind){case CAMP->settings.campStructureId();case TOWN->settings.townHallStructureId();case CIVILIZATION->settings.capitolStructureId();},
                    structures.normalizeRotationDirection(settings.legacyDefaultDirection()),origin.getWorld().getName(),origin.getBlockX(),origin.getBlockY(),origin.getBlockZ(),
                    data.location.getBlockX(),data.location.getBlockY(),data.location.getBlockZ(),data.kind==FoundationKind.TOWN && !data.nativeSettler?settings.townCost():0);
            civ.storage().transaction(db->{FoundationEscrowStore.beginCreation(db,data.escrow,draft);return null;});
            data.creationStarted=true;
        }
        switch (data.kind) {
            case CAMP -> {
                CampRecord camp = creationWrite(data,()->game.createCampFromFoundationAt(player,data.campName,origin));
                data.createdCamp = camp;
                data.createdBuild=structures.buildCampStructureAtOrigin(player, origin).id();
                completeReservation(data);
                tabPrefixes.update(player);
                pending.remove(player.getUniqueId());
                player.sendMessage(component("foundation.camp-created", "&aЛагерь {name} основан.", "name", camp.name()));
            }
            case TOWN -> {
                var civ=plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin modern?modern:null;
                TownRecord town;
                if(data.nativeSettler) {
                    town=creationWrite(data,()->{long id=civ.storage().transaction(db->{SettlerTownStore.requireLocation(db,data.sourceCiv,data.location,settlerFriendlyDistance,settlerEnemyDistance,settlerLandDistance,false,System.currentTimeMillis());return SettlerTownStore.create(db,player.getUniqueId(),data.sourceCiv,data.sourceTown,data.townName,origin,civ.storage().townBaseHammers(settings.townHammersPerHour()),System.currentTimeMillis());});return civ.storage().findTown(id).orElseThrow();});
                }else town=creationWrite(data,()->game.createTownAt(player,data.townName,origin));
                data.createdTown = town;
                data.createdBuild=(data.nativeSettler?structures.buildSettlerTownHallAtOrigin(player,town,origin):structures.buildTownHallStructureAtOrigin(player,town,origin)).id();
                if(data.nativeSettler && data.escrow==null)creationWrite(data,()->civ.storage().transaction(db->{SettlerTownStore.complete(db,player.getUniqueId(),town.id(),data.sourceCiv,data.sourceTown,data.location,System.currentTimeMillis());return null;}));
                completeReservation(data);
                pending.remove(player.getUniqueId());
                player.sendMessage(component("foundation.town-created", "&aГород {name} основан. Строительство ратуши начато.", "name", town.name()));
            }
            case CIVILIZATION -> {
                CivRecord civ = creationWrite(data,()->game.createCivilizationFromFoundation(player,data.civName,data.civTag));
                data.createdCiv = civ;
                TownRecord capital = creationWrite(data,()->game.createCapitalAt(player,data.capitalName,origin));
                data.createdTown = capital;
                data.createdBuild=structures.buildCapitalStructureAtOrigin(player, capital, origin).id();
                completeReservation(data);
                pending.remove(player.getUniqueId());
                player.sendMessage(component("foundation.civ-created", "&aЦивилизация {name} [{tag}] основана. Столица: {capital}. Строительство капитолия начато.", "name", civ.name(), "tag", civ.tag(), "capital", capital.name()));
            }
        }
    }

    private ItemStack consumeOneHand(Player player,EquipmentSlot hand) {
        ItemStack item = player.getInventory().getItem(hand);
        ItemStack consumed = item.clone();
        consumed.setAmount(1);
        int amount = item.getAmount();
        if (amount <= 1) {
            player.getInventory().setItem(hand,null);
        } else {
            item.setAmount(amount - 1);
            player.getInventory().setItem(hand,item);
        }
        player.updateInventory();
        return consumed;
    }

    private void restoreAndRemovePending(Player player, PendingFoundation data) {
        pending.remove(player.getUniqueId());
        restoreConsumedItem(player, data);
        if(data.creationStarted)recoverEscrows();
    }

    private void restoreConsumedItem(Player player, PendingFoundation data) {
        if (data == null || data.success || data.consumedItem == null || data.consumedItem.getType().isAir()) {
            return;
        }
        if(data.escrow!=null && plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin civ) {
            if(data.creationStarted && !data.creationRolledBack){warnRecovery(new IllegalStateException("Foundation creation is unresolved: "+data.escrow));return;}
            try{civ.storage().transaction(db->{FoundationEscrowStore.cancel(db,data.escrow,data.creationRolledBack);return null;});}
            catch(SQLException failure){warnRecovery(failure);}
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
        if(data.creationStarted && data.escrow!=null && plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin civ) {
            // SQL bindings are authoritative, including writes whose acknowledgement was lost.
            try{var plan=civ.storage().query(db->FoundationCreationStore.find(db,data.escrow));if(plan!=null){if(plan.state().equals("COMMITTED"))data.success=true;return;}}
            catch(SQLException failure){warnRecovery(failure);return;}
        }
        if(data.creationWriteInFlight){if(data.createdBuild!=null)structures.stopFoundationBuild(data.createdBuild);warnRecovery(new IllegalStateException("Foundation creation write needs reconciliation: "+data.escrow));return;}
        boolean cleaned=true;
        if(data.createdBuild!=null){structures.stopFoundationBuild(data.createdBuild);data.createdBuild=null;}
        if (data.createdTown != null) {
            try {
            game.rollbackCreatedTown(player, data.createdTown,data.kind==FoundationKind.CIVILIZATION || data.nativeSettler?0:settings.townCost());
            data.createdTown = null;
            } catch (SQLException | RuntimeException exception) {
                cleaned=false;
                plugin.getLogger().warning("Unable to rollback town " + data.createdTown.id() + ": " + exception.getMessage());
            }
        }
        if (data.createdCiv != null && data.createdTown==null) {
            try {
            game.rollbackCreatedCivilization(player, data.createdCiv,0);
            data.createdCiv = null;
            } catch (SQLException | RuntimeException exception) {
                cleaned=false;
                plugin.getLogger().warning("Unable to rollback civilization " + data.createdCiv.id() + ": " + exception.getMessage());
            }
        }
        if (data.createdCamp != null) {
            try {
            game.rollbackCreatedCamp(player, data.createdCamp,0);
            data.createdCamp = null;
            } catch (SQLException | RuntimeException exception) {
                cleaned=false;
                plugin.getLogger().warning("Unable to rollback camp " + data.createdCamp.id() + ": " + exception.getMessage());
            }
        }
        data.creationRolledBack=cleaned;
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
        private boolean nativeSettler;
        private Long sourceTown,sourceCiv,createdBuild;
        private boolean awaitingApproval;
        private long approvalExpires;
        private CampRecord createdCamp;
        private CivRecord createdCiv;
        private TownRecord createdTown;
        private boolean success;
        private String escrow;
        private boolean creationStarted,creationRolledBack,creationWriteInFlight;

        private PendingFoundation(FoundationKind kind, Location location, ItemStack consumedItem) {
            this.kind = kind;
            this.location = location;
            this.consumedItem = consumedItem;
        }
    }
}
