package com.avrgaming.civcraft.modern.trade;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.block.*;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;

/** Frame ejection, item pickup and frame placement with a durable inventory/frame barrier. */
public final class GoodieTransferService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final GoodieFrameService frames;
    private final BonusGoodieItems items;
    private final GoodieDepartureService departures;
    private final GoodieChestService chests;
    private final NamespacedKey applied,dropKey;
    private final Set<UUID> frozen=ConcurrentHashMap.newKeySet();
    private final Map<UUID,Long> clicks=new HashMap<>();
    private final Set<UUID> requestedDrops=new HashSet<>();
    private boolean paused;
    private long lastWarning;
    public GoodieTransferService(CivCraftModernPlugin plugin,StorageBootstrap storage,GoodieFrameService frames)throws SQLException {
        this.plugin=plugin;this.storage=storage;this.frames=frames;
        items=new BonusGoodieItems(plugin,new TradeGoodsCatalog(new java.io.File(plugin.getDataFolder(),"civcraft/data/goods.yml")));
        // Share the market/bank checkpoint so an older feature snapshot cannot overwrite a newer transfer.
        applied=new NamespacedKey(plugin,"last-applied-trade");dropKey=new NamespacedKey(plugin,"goodie-drop");
        for(var operation:storage.query(GoodieTransferStore::pending))frozen.add(operation.player());
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        departures=new GoodieDepartureService(plugin,storage,items);
        chests=new GoodieChestService(plugin,storage,items);
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,80L,20L);
    }
    public void requireUnlocked(UUID player) {
        departures.requireUnlocked(player);
        try{if(storage.query(db->GoodieChestStore.pendingPlayer(db,player)))throw new IllegalArgumentException("Перенос товара через сундук ещё сохраняется.");}
        catch(SQLException failure){throw new IllegalStateException("Cannot validate goodie chest transfer lock",failure);}
        if(frozen.contains(player))throw new IllegalArgumentException("Перенос торгового товара ещё сохраняется.");
    }
    public boolean isGoodie(ItemStack item){return items.token(item)!=null;}
    public GoodieTransferStore.Pending preparePiracy(Connection db,Player player,long build,byte[] inventory,String id)throws SQLException {
        var before=BonusGoodieStore.origin(db,build);if(before==null || before.kind()!=BonusGoodieStore.Kind.SOURCE)throw new IllegalArgumentException("В исходной рамке больше нет товара.");
        var at=player.getLocation();return GoodieTransferStore.prepare(db,id,player.getUniqueId(),before,BonusGoodieStore.Kind.ITEM,id,null,inventory,new GoodieTransferStore.Drop(at.getWorld().getName(),at.getX(),at.getY(),at.getZ()),System.currentTimeMillis());
    }
    public void applyPiracy(Player player,GoodieTransferStore.Pending pending)throws SQLException {
        frozen.add(player.getUniqueId());interrupt("prepared",player);finish(player,pending);
    }
    private void tick() {
        if(paused)return;
        try {
            for(var operation:storage.query(GoodieTransferStore::pending)){var player=Bukkit.getPlayer(operation.player());if(player!=null)finish(player,operation);}
            for(var world:Bukkit.getWorlds())for(var item:world.getEntitiesByClass(Item.class)) {
                String token=items.token(item.getItemStack());if(token==null)continue;
                var state=storage.query(db->BonusGoodieStore.token(db,token));
                if(state==null || (!pendingOrigin(state.origin())&&(!items.matches(item.getItemStack(),state)||state.kind()!=BonusGoodieStore.Kind.ITEM)))item.remove();
            }
        }catch(Exception failure){warn("Goodie transfer recovery failed",failure);}
    }
    private boolean pendingOrigin(long origin)throws SQLException {
        return storage.query(db->GoodieTransferStore.pending(db).stream().anyMatch(p->p.before().origin()==origin));
    }
    private void warn(String message,Exception failure) {
        if(System.currentTimeMillis()-lastWarning>30000){lastWarning=System.currentTimeMillis();plugin.getLogger().severe(message+": "+failure.getMessage());}
    }
    private void authority(Player player,GoodieFrameStore.Frame frame)throws SQLException {
        storage.query(db->{try(var s=db.prepareStatement("SELECT t.mayor_uuid,r.town_id,r.civ_id,t.civ_id,EXISTS(SELECT 1 FROM town_assistants a WHERE a.town_id=t.id AND a.player_uuid=r.uuid) FROM structure_builds b JOIN towns t ON t.id=b.town_id JOIN residents r ON r.uuid=? WHERE b.id=?")) {
            s.setString(1,player.getUniqueId().toString());s.setLong(2,frame.build());try(var r=s.executeQuery()) {
                if(!r.next()||r.getObject(2)==null||r.getObject(3)==null||r.getLong(3)!=r.getLong(4))throw new IllegalArgumentException("Рамкой может пользоваться участник этой цивилизации.");
                if(!player.getUniqueId().toString().equals(r.getString(1))&&!r.getBoolean(5))throw new IllegalArgumentException("Товары в рамках перемещают мэр и его помощники.");
            }
        }return null;});
    }
    private void ready(Player player)throws SQLException {
        requireUnlocked(player.getUniqueId());
        if(plugin.isFoundationPending(player.getUniqueId())||plugin.isWarCampPending(player.getUniqueId()))throw new IllegalArgumentException("Сначала завершите размещение постройки.");
        if(!Set.of(InventoryType.CRAFTING,InventoryType.CREATIVE,InventoryType.PLAYER).contains(player.getOpenInventory().getTopInventory().getType())||!player.getItemOnCursor().getType().isAir())throw new IllegalArgumentException("Закройте инвентарь перед переносом товара.");
        plugin.trades().recover(player);
    }
    private ItemStack[] snapshot(Player player) {
        var result=player.getInventory().getContents();for(int i=0;i<result.length;i++)if(result[i]!=null)result[i]=result[i].clone();return result;
    }
    private void submit(Player player,BonusGoodieStore.State before,BonusGoodieStore.Kind kind,String holder,Long town,
                        ItemStack[] inventory,GoodieTransferStore.Drop drop,String id)throws SQLException {
        var operation=storage.transaction(db->GoodieTransferStore.prepare(db,id,player.getUniqueId(),before,kind,holder,town,ItemStack.serializeItemsAsBytes(inventory),drop,System.currentTimeMillis()));
        frozen.add(player.getUniqueId());interrupt("prepared",player);finish(player,operation);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEntityEvent event) {
        if(frozen.contains(event.getPlayer().getUniqueId())){event.setCancelled(true);return;}
        if(event.getHand()!=EquipmentSlot.HAND||!(event.getRightClicked() instanceof ItemFrame entity))return;
        String key=frames.key(entity);
        if(key==null){if(items.token(event.getPlayer().getInventory().getItemInMainHand())!=null)event.setCancelled(true);return;}
        event.setCancelled(true);var player=event.getPlayer();long now=System.currentTimeMillis();
        if(now-clicks.getOrDefault(player.getUniqueId(),0L)<300)return;clicks.put(player.getUniqueId(),now);
        try {
            ready(player);var frame=storage.query(db->GoodieFrameStore.find(db,key));
            if(frame==null||!entity.getUniqueId().toString().equals(frame.entity()))throw new IllegalArgumentException("Рамка недоступна.");
            authority(player,frame);var inventory=snapshot(player);String id=UUID.randomUUID().toString();
            if(!entity.getItem().getType().isAir()) {
                var before=storage.query(db->BonusGoodieStore.token(db,items.token(entity.getItem())));
                if(!items.matches(entity.getItem(),before)||!(before.kind()==BonusGoodieStore.Kind.FRAME&&before.holder().equals(key)||before.kind()==BonusGoodieStore.Kind.SOURCE&&frame.source()&&before.origin()==frame.build()))throw new IllegalArgumentException("Этот экземпляр товара больше не действует.");
                var location=entity.getLocation();var drop=new GoodieTransferStore.Drop(location.getWorld().getName(),location.getX(),location.getY(),location.getZ());
                submit(player,before,BonusGoodieStore.Kind.ITEM,id,null,inventory,drop,id);
            }else {
                int slot=player.getInventory().getHeldItemSlot();var held=inventory[slot];
                var before=storage.query(db->BonusGoodieStore.token(db,items.token(held)));
                if(!items.matches(held,before)||before.kind()!=BonusGoodieStore.Kind.PLAYER||!before.holder().equals(player.getUniqueId().toString()))throw new IllegalArgumentException("Возьмите действующий торговый товар в руку.");
                var kind=frame.source()&&frame.build()==before.origin()?BonusGoodieStore.Kind.SOURCE:BonusGoodieStore.Kind.FRAME;
                Long town=frame.source()?null:storage.query(db->{try(var s=db.prepareStatement("SELECT town_id FROM structure_builds WHERE id=?")){s.setLong(1,frame.build());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Frame building disappeared");return r.getLong(1);}}});
                inventory[slot]=null;submit(player,before,kind,kind==BonusGoodieStore.Kind.SOURCE?Long.toString(before.origin()):key,town,inventory,null,id);
            }
        }catch(IllegalArgumentException failure){player.sendMessage(Component.text(failure.getMessage()));}
        catch(Exception failure){fail(player,failure);}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void pickup(EntityPickupItemEvent event) {
        var entity=event.getItem();String token=items.token(entity.getItemStack());if(token==null)return;
        event.setCancelled(true);if(!(event.getEntity() instanceof Player player))return;
        try {
            ready(player);var before=storage.query(db->BonusGoodieStore.token(db,token));
            if(before==null||!items.matches(entity.getItemStack(),before)||before.kind()!=BonusGoodieStore.Kind.ITEM||!before.holder().equals(entity.getPersistentDataContainer().get(dropKey,PersistentDataType.STRING))) {
                if(before==null||!pendingOrigin(before.origin()))entity.remove();return;
            }
            if(pendingOrigin(before.origin()))return;
            var inventory=snapshot(player);int slot=-1;for(int i=0;i<36;i++)if(inventory[i]==null||inventory[i].getType().isAir()){slot=i;break;}
            if(slot<0)return;
            var after=new BonusGoodieStore.State(before.origin(),before.good(),before.token(),before.revision()+1,BonusGoodieStore.Kind.PLAYER,player.getUniqueId().toString(),null);
            inventory[slot]=items.create(after);submit(player,before,after.kind(),after.holder(),null,inventory,null,UUID.randomUUID().toString());
        }catch(IllegalArgumentException ignored){}catch(Exception failure){fail(player,failure);}
    }
    private void finish(Player player,GoodieTransferStore.Pending operation)throws SQLException {
        if(storage.query(db->GoodieTransferStore.pending(db).stream().noneMatch(p->p.id().equals(operation.id()))))return;
        frozen.add(player.getUniqueId());var current=storage.query(db->BonusGoodieStore.origin(db,operation.before().origin()));
        if(current!=null&&!current.equals(operation.before()))throw new SQLException("Goodie changed before world recovery");
        boolean invalid=current==null;var contents=ItemStack.deserializeItemsFromBytes(operation.inventory());
        if(contents.length!=player.getInventory().getSize())throw new IllegalArgumentException("Invalid goodie inventory image");
        if(invalid)for(int i=0;i<contents.length;i++)if(operation.before().token().equals(items.token(contents[i])))contents[i]=null;
        var worlds=new HashSet<World>();
        if(operation.fromFrame()!=null) {
            var frame=frames.resolve(operation.fromFrame(),false);
            if(frame!=null){if(!frame.getItem().getType().isAir()&&!operation.before().token().equals(items.token(frame.getItem())))throw new IllegalStateException("Source frame contains another item");frame.setItem(null,false);worlds.add(frame.getWorld());}
        }
        if(operation.before().kind()==BonusGoodieStore.Kind.ITEM)for(var world:Bukkit.getWorlds())for(var item:world.getEntitiesByClass(Item.class))
            if(operation.before().holder().equals(item.getPersistentDataContainer().get(dropKey,PersistentDataType.STRING))){item.remove();worlds.add(world);}
        if(operation.toFrame()!=null&&!invalid) {
            var frame=frames.resolve(operation.toFrame(),true);if(frame==null)throw new IllegalStateException("Goodie destination frame disappeared");
            if(!frame.getItem().getType().isAir()&&!operation.before().token().equals(items.token(frame.getItem())))throw new IllegalStateException("Destination frame contains another item");
            frame.setItem(items.create(operation.after()),false);worlds.add(frame.getWorld());
        }
        if(operation.drop()!=null&&!invalid) {
            var drop=operation.drop();var world=Bukkit.getWorld(drop.world());if(world==null)throw new IllegalStateException("Goodie drop world unavailable");
            var location=new Location(world,drop.x(),drop.y(),drop.z());world.getChunkAt(location.getBlockX()>>4,location.getBlockZ()>>4);
            boolean exists=world.getEntitiesByClass(Item.class).stream().anyMatch(item->operation.id().equals(item.getPersistentDataContainer().get(dropKey,PersistentDataType.STRING)));
            if(!exists)world.dropItem(location,items.create(operation.after()),item->{item.getPersistentDataContainer().set(dropKey,PersistentDataType.STRING,operation.id());item.setPickupDelay(10);});worlds.add(world);
        }
        for(var world:worlds)world.save(true);interrupt("world-saved",player);
        if(invalid||!operation.id().equals(player.getPersistentDataContainer().get(applied,PersistentDataType.STRING))) {
            player.getInventory().setContents(contents);player.getPersistentDataContainer().set(applied,PersistentDataType.STRING,operation.id());player.saveData();
        }
        interrupt("player-saved",player);byte[] image=ItemStack.serializeItemsAsBytes(contents);
        storage.transaction(db->{var result=GoodieTransferStore.complete(db,operation.id());if(result!=GoodieTransferStore.Completion.ALREADY_COMPLETE)TradeStore.saveCheckpoint(db,player.getUniqueId().toString(),operation.id(),image);return null;});
        frozen.remove(player.getUniqueId());player.sendMessage(Component.text(invalid?"Торговый товар больше не действует.":"Торговый товар перенесён."));
    }
    private void interrupt(String phase,Player player) {
        if(player.getName().equals(System.getProperty("civcraft.test.goodie.player"))&&phase.equals(System.getProperty("civcraft.test.goodie.interruption"))) {
            paused=true;throw new IllegalStateException("Injected goodie interruption at "+phase);
        }
    }
    private void fail(Player player,Exception failure) {
        warn("Goodie transfer failed for "+player.getUniqueId(),failure);
        if(frozen.contains(player.getUniqueId()))player.kick(Component.text("Перенос товара сохраняется. Переподключитесь после восстановления сервера."));
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void join(PlayerJoinEvent e) {
        try {if(!paused)for(var operation:storage.query(GoodieTransferStore::pending))if(operation.player().equals(e.getPlayer().getUniqueId()))finish(e.getPlayer(),operation);}
        catch(Exception failure){fail(e.getPlayer(),failure);}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void click(InventoryClickEvent e) {
        if(e.getWhoClicked() instanceof Player p && frozen.contains(p.getUniqueId())){e.setCancelled(true);return;}
        if(chests.click(e))return;
        if(e.getView().getTopInventory().getType()!=InventoryType.CRAFTING&&(items.token(e.getCurrentItem())!=null||items.token(e.getCursor())!=null))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drag(InventoryDragEvent e){if(frozen.contains(e.getWhoClicked().getUniqueId())){e.setCancelled(true);return;}if(chests.drag(e))return;if(items.token(e.getOldCursor())!=null)e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void open(InventoryOpenEvent e){if(frozen.contains(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void swap(PlayerSwapHandItemsEvent e){if(frozen.contains(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drop(PlayerDropItemEvent e) {
        var player=e.getPlayer();if(frozen.contains(player.getUniqueId()) || chests.frozen(player.getUniqueId())){e.setCancelled(true);return;}
        String token=items.token(e.getItemDrop().getItemStack());if(token==null)return;
        // Cancellation restores the item first. The next tick journals the real inventory,
        // instead of recording Bukkit's transient inventory during PlayerDropItemEvent.
        e.setCancelled(true);if(!requestedDrops.add(player.getUniqueId()))return;
        var at=e.getItemDrop().getLocation();var where=new GoodieTransferStore.Drop(at.getWorld().getName(),at.getX(),at.getY(),at.getZ());
        plugin.getServer().getScheduler().runTask(plugin,()->{
            requestedDrops.remove(player.getUniqueId());if(!player.isOnline() || player.isDead() || paused)return;
            try {
                ready(player);var before=storage.query(db->BonusGoodieStore.token(db,token));
                if(before==null || before.kind()!=BonusGoodieStore.Kind.PLAYER || !before.holder().equals(player.getUniqueId().toString()))throw new IllegalArgumentException("Этот торговый товар больше не принадлежит вашему инвентарю.");
                var inventory=snapshot(player);int slot=-1;
                for(int i=0;i<inventory.length;i++)if(token.equals(items.token(inventory[i]))){if(slot>=0 || !items.matches(inventory[i],before))throw new IllegalArgumentException("Обнаружена недействительная копия торгового товара.");slot=i;}
                if(slot<0)throw new IllegalArgumentException("Торгового товара больше нет в инвентаре.");
                inventory[slot]=null;String id=UUID.randomUUID().toString();submit(player,before,BonusGoodieStore.Kind.ITEM,id,null,inventory,where,id);
            }catch(IllegalArgumentException failure){player.sendMessage(Component.text(failure.getMessage()));}catch(Exception failure){fail(player,failure);}
        });
    }
    /** Rotating the canonical token first makes an old saved ground entity harmless. */
    private boolean returnGround(Item item)throws SQLException {
        String token=items.token(item.getItemStack());if(token==null)return true;
        return storage.transaction(db->{
            var before=BonusGoodieStore.token(db,token);if(before==null)return true;
            if(GoodieTransferStore.pending(db).stream().anyMatch(p->p.before().origin()==before.origin()))return false;
            if(items.matches(item.getItemStack(),before) && before.kind()==BonusGoodieStore.Kind.ITEM && before.holder().equals(item.getPersistentDataContainer().get(dropKey,PersistentDataType.STRING)))BonusGoodieStore.returnHome(db,before);
            return true;
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void despawn(ItemDespawnEvent e) {
        if(items.token(e.getEntity().getItemStack())==null)return;
        try{if(!returnGround(e.getEntity()))e.setCancelled(true);}catch(SQLException failure){e.setCancelled(true);warn("Goodie despawn recovery failed",failure);}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void combust(EntityCombustEvent e) {
        if(!(e.getEntity() instanceof Item item) || items.token(item.getItemStack())==null)return;
        e.setCancelled(true);try{if(returnGround(item))item.remove();}catch(SQLException failure){warn("Goodie fire recovery failed",failure);}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void groundDamage(EntityDamageEvent e) {
        if(e.getCause()!=EntityDamageEvent.DamageCause.VOID || !(e.getEntity() instanceof Item item) || items.token(item.getItemStack())==null)return;
        e.setCancelled(true);try{if(returnGround(item))item.remove();}catch(SQLException failure){warn("Goodie void recovery failed",failure);}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void unload(org.bukkit.event.world.ChunkUnloadEvent e) {
        for(var entity:e.getChunk().getEntities())if(entity instanceof Item item && items.token(item.getItemStack())!=null)try {
            // Modern Paper cannot cancel ChunkUnloadEvent. An unfinished transfer's
            // item remains in the chunk save and is recovered when entities load again.
            if(returnGround(item))item.remove();
        }catch(SQLException failure){warn("Goodie chunk unload recovery failed",failure);}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void merge(ItemMergeEvent e){if(items.token(e.getEntity().getItemStack())!=null || items.token(e.getTarget().getItemStack())!=null)e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void hopper(InventoryPickupItemEvent e){if(items.token(e.getItem().getItemStack())!=null)e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void command(PlayerCommandPreprocessEvent e){if(frozen.contains(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void use(PlayerInteractEvent e){if(frozen.contains(e.getPlayer().getUniqueId())||items.token(e.getItem())!=null)e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void consume(PlayerItemConsumeEvent e){if(frozen.contains(e.getPlayer().getUniqueId())||items.token(e.getItem())!=null)e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void interactAt(PlayerInteractAtEntityEvent e){interact(e);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void arrow(PlayerPickupArrowEvent e){if(frozen.contains(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void attack(EntityDamageByEntityEvent e){if(e.getDamager() instanceof Player p&&frozen.contains(p.getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void damage(EntityDamageEvent e){if(e.getEntity() instanceof Player p&&frozen.contains(p.getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void block(BlockBreakEvent e){if(frozen.contains(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void block(BlockPlaceEvent e){if(frozen.contains(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void craft(CraftItemEvent e){for(var item:e.getInventory().getMatrix())if(items.token(item)!=null){e.setCancelled(true);break;}}
}
