package com.avrgaming.civcraft.modern.war;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;

public final class WarCampItemService implements Listener {
    private final CivCraftModernPlugin plugin;private final StorageBootstrap storage;private final NamespacedKey applied;
    private final Map<UUID,List<Location>> previews=new ConcurrentHashMap<>();
    private final Map<UUID,UUID> checking=new ConcurrentHashMap<>();
    private final int maximum,timeout,controlHp;
    public boolean hasPending(UUID player){return previews.containsKey(player)||checking.containsKey(player);}
    public WarCampItemService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;applied=new NamespacedKey(plugin,"warcamp-item-applied");
        var config=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("civcraft/data/war.yml").toFile());maximum=config.getInt("warcamp.max",3);timeout=config.getInt("warcamp.rebuild_timeout",30);controlHp=config.getInt("warcamp.control_block_hitpoints",20);
        storage.transaction(db->{WarCampItemStore.install(db);return null;});plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    private boolean held(Player player){return "mat_found_warcamp".equals(plugin.nativeItems().id(player.getInventory().getItemInMainHand()));}
    @EventHandler(priority=EventPriority.HIGH) public void use(PlayerInteractEvent event) {
        if(event.getHand()!=EquipmentSlot.HAND||!Set.of(Action.RIGHT_CLICK_AIR,Action.RIGHT_CLICK_BLOCK).contains(event.getAction())||!held(event.getPlayer()))return;
        event.setCancelled(true);var player=event.getPlayer();
        if(plugin.isWallPending(player.getUniqueId())){player.sendMessage(Component.text("Сначала завершите разметку стены или отмените её: /b cancel."));return;}
        if(plugin.isFoundationPending(player.getUniqueId()))return;
        if(checking.containsKey(player.getUniqueId())){player.sendMessage(Component.text("Проверка опоры военного лагеря уже выполняется."));return;}
        if(previews.containsKey(player.getUniqueId())){player.sendMessage(Component.text("Введите yes для основания военного лагеря или любой другой ответ для отмены."));return;}
        try {
            recover(player);if(!held(player))return;var plan=plugin.warCampPlacement().plan(player);
            validate(player,plan,()->showPreview(player,plan));
        }catch(IllegalArgumentException failure){player.sendMessage(Component.text(failure.getMessage()));}
        catch(Exception failure){plugin.getLogger().severe("War camp preview failed: "+failure.getMessage());player.sendMessage(Component.text("Не удалось проверить военный лагерь."));}
    }
    private void showPreview(Player player,WarCampPlacement.Plan plan) {
            var outline=new ArrayList<Location>();
            int minX=plan.blocks().stream().mapToInt(WarCampStore.Block::x).min().orElseThrow(),maxX=plan.blocks().stream().mapToInt(WarCampStore.Block::x).max().orElseThrow(),minY=plan.origin().y(),maxY=plan.blocks().stream().mapToInt(WarCampStore.Block::y).max().orElseThrow(),minZ=plan.blocks().stream().mapToInt(WarCampStore.Block::z).min().orElseThrow(),maxZ=plan.blocks().stream().mapToInt(WarCampStore.Block::z).max().orElseThrow();
            for(var block:plan.blocks()){int edges=(block.x()==minX||block.x()==maxX?1:0)+(block.y()==minY||block.y()==maxY?1:0)+(block.z()==minZ||block.z()==maxZ?1:0);if(edges>=2)outline.add(new Location(player.getWorld(),block.x(),block.y(),block.z()));}
            previews.put(player.getUniqueId(),List.copyOf(outline));for(var location:outline)player.sendBlockChange(location,Material.GLASS.createBlockData());
            player.sendMessage(Component.text("Основание военного лагеря. Будет израсходована вся стопка в основной руке: "+player.getInventory().getItemInMainHand().getAmount()+". Пауза повторной постройки: "+timeout+" мин. Введите yes для подтверждения; любой другой ответ отменит действие."));
    }
    private void validate(Player player,WarCampPlacement.Plan plan,Runnable success) {
        if(!plugin.warCampPlacement().foundationEnabled()){success.run();return;}
        var world=player.getWorld();var snapshot=FoundationSnapshotValidator.capture(world,plan.foundation(),plan.origin().y());var token=UUID.randomUUID();checking.put(player.getUniqueId(),token);
        player.sendMessage(Component.text("Проверяется опора военного лагеря..."));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,()->{
            var result=snapshot.validate();plugin.getServer().getScheduler().runTask(plugin,()->{
                if(!checking.remove(player.getUniqueId(),token)||!player.isOnline())return;
                try {
                    if(!held(player))throw new IllegalArgumentException("Предмет военного лагеря должен оставаться в основной руке.");
                    var fresh=plugin.warCampPlacement().plan(player);
                    if(!player.getWorld().equals(world)||!fresh.origin().equals(plan.origin()))throw new IllegalArgumentException("Место лагеря изменилось во время проверки. Повторите ПКМ.");
                    if(!result.valid()){player.sendMessage(Component.text(result.message()));if(!player.isOp())return;player.sendMessage(Component.text("Оператор: результат проверки опоры принят принудительно."));}
                    success.run();
                }catch(IllegalArgumentException failure){player.sendMessage(Component.text("Проверка лагеря отклонена: "+failure.getMessage()));}
                catch(Exception failure){plugin.getLogger().severe("War camp foundation validation failed: "+failure.getMessage());player.sendMessage(Component.text("Не удалось завершить проверку лагеря. Подробности находятся в журнале сервера."));}
            });
        });
    }
    @EventHandler public void chat(AsyncChatEvent event) {
        if(!previews.containsKey(event.getPlayer().getUniqueId()))return;event.setCancelled(true);String answer=PlainTextComponentSerializer.plainText().serialize(event.message()).trim();plugin.getServer().getScheduler().runTask(plugin,()->confirm(event.getPlayer(),answer));
    }
    private void clearPreview(Player player) {
        var outline=previews.remove(player.getUniqueId());if(outline!=null&&player.isOnline())for(var location:outline)player.sendBlockChange(location,location.getBlock().getBlockData());
    }
    private void confirm(Player player,String answer) {
        if(!previews.containsKey(player.getUniqueId()))return;clearPreview(player);
        if(!answer.equalsIgnoreCase("yes")){player.sendMessage(Component.text("Основание военного лагеря отменено."));return;}
        try {
            recover(player);
            if(!held(player))throw new IllegalArgumentException("Предмет военного лагеря должен оставаться в основной руке.");
            if(!player.getItemOnCursor().getType().isAir())throw new IllegalArgumentException("Закройте инвентарь перед основанием лагеря.");
            var plan=plugin.warCampPlacement().plan(player);validate(player,plan,()->build(player));
        }catch(IllegalArgumentException failure){player.sendMessage(Component.text(failure.getMessage()));}
        catch(Exception failure){plugin.getLogger().severe("War camp confirmation failed: "+failure.getMessage());player.sendMessage(Component.text("Не удалось проверить военный лагерь."));}
    }
    private void build(Player player) {
        try {
            if(!player.getItemOnCursor().getType().isAir())throw new IllegalArgumentException("Закройте инвентарь перед основанием лагеря.");
            var plan=plugin.warCampPlacement().plan(player);var origin=plan.origin();String id=UUID.randomUUID().toString();
            ItemStack[] after=Arrays.stream(player.getInventory().getContents()).map(stack->stack==null?null:stack.clone()).toArray(ItemStack[]::new);after[player.getInventory().getHeldItemSlot()]=null;byte[] inventory=ItemStack.serializeItemsAsBytes(after);
            var receipt=storage.transaction(db->{
                var camp=WarCampStore.prepare(db,id,player.getUniqueId(),player.getWorld().getName(),origin.x(),origin.y(),origin.z(),plan.blocks(),maximum,timeout,System.currentTimeMillis());
                for(var marker:plan.markers())WarCampStore.point(db,camp.id(),marker.kind(),player.getWorld().getName(),marker.x(),marker.y()+(marker.kind().equals("control")?1:0),marker.z(),marker.kind().equals("control")?controlHp:0);
                return WarCampItemStore.prepare(db,id,camp.id(),player.getUniqueId().toString(),inventory,System.currentTimeMillis());
            });
            apply(player,receipt);player.sendMessage(Component.text("Военный лагерь #"+receipt.camp()+" поставлен на строительство."));
        }catch(IllegalArgumentException failure){player.sendMessage(Component.text(failure.getMessage()));}
        catch(Exception failure){plugin.getLogger().severe("War camp item transaction retained: "+failure.getMessage());player.kick(Component.text("Основание лагеря приостановлено. Переподключитесь для восстановления."));}
    }
    private void inventory(Player player,WarCampItemStore.Receipt receipt) {
        var contents=ItemStack.deserializeItemsFromBytes(receipt.inventory());if(contents.length!=player.getInventory().getSize())throw new IllegalArgumentException("Неверный размер сохранённого инвентаря лагеря");
        player.getInventory().setContents(contents);player.getPersistentDataContainer().set(applied,PersistentDataType.STRING,receipt.id());player.saveData();
    }
    private void apply(Player player,WarCampItemStore.Receipt receipt)throws SQLException {
        interruption("prepared",player);
        if(!receipt.id().equals(player.getPersistentDataContainer().get(applied,PersistentDataType.STRING)))inventory(player,receipt);
        interruption("player-saved",player);storage.transaction(db->{WarCampItemStore.finish(db,receipt);return null;});
    }
    private void recover(Player player)throws SQLException {
        var pending=storage.query(db->WarCampItemStore.pending(db,player.getUniqueId().toString()));if(pending!=null)apply(player,pending);
        else{var checkpoint=storage.query(db->WarCampItemStore.checkpoint(db,player.getUniqueId().toString()));if(checkpoint!=null&&!checkpoint.id().equals(player.getPersistentDataContainer().get(applied,PersistentDataType.STRING)))inventory(player,checkpoint);}
    }
    private void interruption(String phase,Player player) {
        if(player.getName().equals(System.getProperty("civcraft.test.warcamp.item.player"))&&phase.equals(System.getProperty("civcraft.test.warcamp.item.interruption"))){System.clearProperty("civcraft.test.warcamp.item.interruption");throw new IllegalStateException("Injected camp item interruption at "+phase);}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void join(PlayerJoinEvent event){try{recover(event.getPlayer());}catch(Exception failure){plugin.getLogger().severe("Camp item recovery failed: "+failure.getMessage());event.getPlayer().kick(Component.text("Не удалось восстановить основание лагеря."));}}
    @EventHandler public void quit(PlayerQuitEvent event){checking.remove(event.getPlayer().getUniqueId());clearPreview(event.getPlayer());}
}
