package com.avrgaming.civcraft.modern.trade;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.SQLException;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;

/** Native chest clicks are simulated, journaled and applied after Bukkit finishes the event. */
public final class GoodieChestService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final BonusGoodieItems items;
    private final NamespacedKey applied,chestApplied;
    private final PlainCursorPersistence plainCursors;
    private final Set<UUID> frozen=new HashSet<>();
    private final Set<UUID> cursorOwners=new HashSet<>();
    private final Map<String,GoodieChestStore.Position> knownContainers=new HashMap<>();
    private final Deque<String> checkContainers=new ArrayDeque<>();
    private Set<GoodieChestStore.Position> locks=Set.of();
    private boolean paused;
    private long lastWarning;
    private record Half(GoodieChestStore.Position position,String material) {}
    private record Located(ItemStack item,BonusGoodieStore.Kind kind,String holder) {}
    public GoodieChestService(CivCraftModernPlugin plugin,StorageBootstrap storage,BonusGoodieItems items)throws SQLException {
        this.plugin=plugin;this.storage=storage;this.items=items;applied=new NamespacedKey(plugin,"last-applied-trade");chestApplied=new NamespacedKey(plugin,"last-goodie-chest-transfer");
        plainCursors=new PlainCursorPersistence(plugin,this::good,this::cursorFrozen);
        refreshLocks();knownContainers.putAll(storage.query(GoodieChestStore::knownContainers));cursorOwners.addAll(storage.query(db->{var out=new ArrayList<UUID>();try(var s=db.createStatement();var r=s.executeQuery("SELECT player_uuid FROM goodie_chest_cursor_checkpoints")){while(r.next())out.add(UUID.fromString(r.getString(1)));}return out;}));plugin.getServer().getPluginManager().registerEvents(this,plugin);plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,20L,20L);
        plugin.getServer().getScheduler().runTask(plugin,()->{for(var player:Bukkit.getOnlinePlayers())try{recoverPlainDelivery(player);plainCursors.restore(player);}catch(Exception failure){warn(failure);}});
    }
    public boolean frozen(UUID player){return frozen.contains(player);}
    private boolean cursorFrozen(UUID player){try{return storage.query(db->GoodieChestStore.pendingPlayer(db,player));}catch(SQLException failure){warn(failure);return true;}}
    private void refreshLocks()throws SQLException {locks=storage.query(GoodieChestStore::locked);frozen.clear();for(var p:storage.query(GoodieChestStore::pending))frozen.add(p.player());}
    private void warn(Exception failure){long now=System.currentTimeMillis();if(now-lastWarning<30000)return;lastWarning=now;plugin.getLogger().severe("Goodie chest recovery failed: "+failure.getMessage());}
    private boolean good(ItemStack item){return items.token(item)!=null;}
    private Half half(InventoryHolder holder) {
        if(!(holder instanceof Chest chest))throw new IllegalArgumentException("Товар можно хранить только в сундуке.");
        var b=chest.getBlock();if(b.getType()!=Material.CHEST && b.getType()!=Material.TRAPPED_CHEST)throw new IllegalArgumentException("Товар можно хранить только в сундуке.");
        return new Half(new GoodieChestStore.Position(b.getWorld().getName(),b.getX(),b.getY(),b.getZ()),b.getType().name());
    }
    private List<Half> halves(Inventory inventory) {
        if(inventory instanceof DoubleChestInventory doubleChest)return List.of(half(doubleChest.getLeftSide().getHolder()),half(doubleChest.getRightSide().getHolder()));
        return List.of(half(inventory.getHolder()));
    }
    private boolean personal(InventoryType type){return type==InventoryType.CRAFTING || type==InventoryType.CREATIVE || type==InventoryType.PLAYER;}
    private GoodieChestStore.Position position(Block block){return new GoodieChestStore.Position(block.getWorld().getName(),block.getX(),block.getY(),block.getZ());}
    private boolean locked(Inventory inventory){var holder=inventory.getHolder();if(holder instanceof Chest chest)return locks.contains(position(chest.getBlock()));if(holder instanceof DoubleChest chest)return locked(chest.getLeftSide()) || locked(chest.getRightSide());return false;}
    private boolean locked(InventoryHolder holder){return holder instanceof Chest chest && locks.contains(position(chest.getBlock()));}
    private void ready(Player player)throws SQLException {
        if(paused)throw new IllegalArgumentException("Переносы товаров приостановлены до восстановления сервера.");
        plugin.goodieTransfers().requireUnlocked(player.getUniqueId());
        if(plugin.isFoundationPending(player.getUniqueId()) || plugin.isWarCampPending(player.getUniqueId()))throw new IllegalArgumentException("Сначала завершите размещение постройки.");
        plugin.trades().recover(player);
    }
    /** Returns true when the old blanket restriction must not handle this action again. */
    public boolean click(InventoryClickEvent event) {
        if(!(event.getWhoClicked() instanceof Player player))return false;
        if(frozen.contains(player.getUniqueId()) || locked(event.getView().getTopInventory())){event.setCancelled(true);return true;}
        int hotbar=event.getClick()==ClickType.SWAP_OFFHAND?40:event.getHotbarButton();
        boolean touches=switch(event.getAction()) {
            case MOVE_TO_OTHER_INVENTORY,DROP_ALL_SLOT,DROP_ONE_SLOT -> good(event.getCurrentItem());
            case HOTBAR_SWAP,HOTBAR_MOVE_AND_READD -> good(event.getCurrentItem()) || hotbar>=0 && good(player.getInventory().getItem(hotbar));
            default -> good(event.getCurrentItem()) || good(event.getCursor());
        };
        if(!touches)return good(event.getCursor()); // An ordinary shift/drop does not move the held good.
        if(event.getAction()==InventoryAction.COLLECT_TO_CURSOR && !good(event.getCursor()))return true;
        event.setCancelled(true);
        try {
            ready(player);var upper=event.getView().getTopInventory();var parts=personal(upper.getType())?List.<Half>of():halves(upper);
            boolean top=event.getClickedInventory()==upper;int slot=event.getClickedInventory()==null?-1:event.getSlot();
            if(top && parts.isEmpty())throw new IllegalArgumentException("Торговый товар нельзя помещать в сетку крафта.");
            if(parts.isEmpty() && event.isShiftClick())return true;
            var before=new GoodieChestPlan(parts.isEmpty()?new ItemStack[0]:upper.getContents(),player.getInventory().getContents(),event.getCursor());
            var after=new GoodieChestPlan(before.top,before.player,before.cursor);after.click(event.getAction(),top,slot,hotbar);
            submit(player,parts,before,after);
        }catch(IllegalArgumentException failure){player.sendMessage(Component.text(failure.getMessage()));}catch(Exception failure){warn(failure);}
        return true;
    }
    public boolean drag(InventoryDragEvent event) {
        if(!(event.getWhoClicked() instanceof Player player))return false;
        if(frozen.contains(player.getUniqueId()) || locked(event.getView().getTopInventory())){event.setCancelled(true);return true;}
        if(!good(event.getOldCursor()))return false;
        event.setCancelled(true);
        try {
            ready(player);var upper=event.getView().getTopInventory();var parts=personal(upper.getType())?List.<Half>of():halves(upper);
            var before=new GoodieChestPlan(parts.isEmpty()?new ItemStack[0]:upper.getContents(),player.getInventory().getContents(),event.getOldCursor());var after=new GoodieChestPlan(before.top,before.player,before.cursor);
            for(var entry:event.getNewItems().entrySet()) {
                boolean top=event.getView().getInventory(entry.getKey())==upper;
                if(top && parts.isEmpty())throw new IllegalArgumentException("Торговый товар нельзя помещать в сетку крафта.");
                after.set(top,event.getView().convertSlot(entry.getKey()),entry.getValue());
            }
            after.cursor=GoodieChestPlan.clone(event.getCursor());submit(player,parts,before,after);
        }catch(IllegalArgumentException failure){player.sendMessage(Component.text(failure.getMessage()));}catch(Exception failure){warn(failure);}return true;
    }
    private Map<String,Located> locate(UUID player,List<Half> halves,GoodieChestPlan plan,boolean includeDrops,String operation) {
        var found=new LinkedHashMap<String,Located>();
        for(int i=0;i<plan.top.length;i++)locate(found,plan.top[i],BonusGoodieStore.Kind.CONTAINER,halves.get(i/27).position().holder());
        for(var item:plan.player)locate(found,item,BonusGoodieStore.Kind.PLAYER,player.toString());locate(found,plan.cursor,BonusGoodieStore.Kind.PLAYER,player.toString());
        if(includeDrops)for(var item:plan.drops){Long origin=items.origin(item);if(origin==null)throw new IllegalArgumentException("Недопустимое выпадение при переносе товара.");locate(found,item,BonusGoodieStore.Kind.ITEM,operation+":"+origin);}
        return found;
    }
    private void locate(Map<String,Located> found,ItemStack item,BonusGoodieStore.Kind kind,String holder) {
        String token=items.token(item);if(token==null)return;
        if(found.putIfAbsent(token,new Located(item,kind,holder))!=null)throw new IllegalArgumentException("Обнаружена лишняя копия торгового товара.");
    }
    private void submit(Player player,List<Half> halves,GoodieChestPlan before,GoodieChestPlan after)throws SQLException {
        submit(player,halves,before,after,(db,id)->{});
    }
    @FunctionalInterface private interface IntentSideEffect {void prepare(java.sql.Connection db,String id)throws SQLException;}
    public String submitCampQuestItems(Player player,ItemStack[] inventory,long camp,String quest,int beforeAmount,int removed,int target)throws SQLException {
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Quest inventory mutations require the server thread");
        ready(player);
        if(!plugin.getCampApi().canApplyCampQuest(camp,player.getUniqueId()))throw new IllegalArgumentException("Инвентари лагеря ещё сохраняются.");
        if(inventory.length!=player.getInventory().getSize())throw new IllegalArgumentException("Invalid quest inventory snapshot");
        var before=new GoodieChestPlan(new ItemStack[0],player.getInventory().getContents(),player.getItemOnCursor());
        var after=new GoodieChestPlan(new ItemStack[0],inventory,player.getItemOnCursor());
        return submit(player,List.of(),before,after,(db,id)->com.avrgaming.civcraft.modern.campnpc.quest.QuestSubmissionStore.prepare(db,id,camp,quest,player.getUniqueId(),beforeAmount,removed,target));
    }
    private String submit(Player player,List<Half> halves,GoodieChestPlan before,GoodieChestPlan after,IntentSideEffect sideEffect)throws SQLException {
        if(after.drops.isEmpty() && Arrays.equals(before.top,after.top) && Arrays.equals(before.player,after.player) && Objects.equals(before.cursor,after.cursor))return null;
        validateUnits(player,before.player,after.player);
        String id=UUID.randomUUID().toString();var initial=locate(player.getUniqueId(),halves,before,false,id);var result=locate(player.getUniqueId(),halves,after,true,id);
        if(!initial.keySet().equals(result.keySet()))throw new IllegalArgumentException("Товар нельзя разделить или удалить этим действием.");
        var moves=new ArrayList<GoodieChestStore.Move>();var replacements=new HashMap<String,ItemStack>();var at=player.getLocation();var drop=new GoodieTransferStore.Drop(at.getWorld().getName(),at.getX(),at.getY(),at.getZ());
        for(var entry:initial.entrySet()) {
            var state=storage.query(db->BonusGoodieStore.token(db,entry.getKey()));var was=entry.getValue();var next=result.get(entry.getKey());
            if(!items.matches(was.item(),state) || state.kind()!=was.kind() || !state.holder().equals(was.holder()))throw new IllegalArgumentException("Товар больше не находится в этом инвентаре.");
            var move=new GoodieChestStore.Move(state,next.kind(),next.holder(),next.kind()==BonusGoodieStore.Kind.ITEM?drop:null);moves.add(move);replacements.put(entry.getKey(),items.create(move.after()));
        }
        replace(after.top,replacements);replace(after.player,replacements);if(good(after.cursor))after.cursor=replacements.get(items.token(after.cursor)).clone();
        var images=new ArrayList<GoodieChestStore.Image>();
        for(int i=0;i<halves.size();i++)images.add(new GoodieChestStore.Image(halves.get(i).position(),halves.get(i).material(),ItemStack.serializeItemsAsBytes(Arrays.copyOfRange(before.top,i*27,i*27+27)),ItemStack.serializeItemsAsBytes(Arrays.copyOfRange(after.top,i*27,i*27+27))));
        String cursorToken=items.token(after.cursor);Long cursorRevision=cursorToken==null?null:moves.stream().filter(m->m.before().token().equals(cursorToken)).findFirst().orElseThrow().after().revision();
        var intent=storage.transaction(db->{var saved=GoodieChestStore.prepare(db,id,player.getUniqueId(),ItemStack.serializeItemsAsBytes(after.player),ItemStack.serializeItemsAsBytes(new ItemStack[]{after.cursor}),images,moves,cursorToken,cursorRevision,System.currentTimeMillis());sideEffect.prepare(db,id);return saved;});
        frozen.add(player.getUniqueId());var reserved=new HashSet<>(locks);for(var image:images){reserved.add(image.position());knownContainers.put(image.position().holder(),image.position());}locks=Set.copyOf(reserved);interrupt("prepared",intent);plugin.getServer().getScheduler().runTask(plugin,()->{try{if(player.isOnline() && !player.isDead() && !paused)finish(player,intent);}catch(Exception failure){warn(failure);}});
        return id;
    }
    private void replace(ItemStack[] inventory,Map<String,ItemStack> replacements){for(int i=0;i<inventory.length;i++)if(good(inventory[i]))inventory[i]=replacements.get(items.token(inventory[i])).clone();}
    private void filter(ItemStack[] inventory,Set<String> invalid){for(int i=0;i<inventory.length;i++)if(invalid.contains(items.token(inventory[i])))inventory[i]=null;}
    private Chest chest(GoodieChestStore.Image image) {
        var p=image.position();var world=Bukkit.getWorld(p.world());if(world==null)throw new IllegalStateException("Chest world is unavailable");
        var b=world.getBlockAt(p.x(),p.y(),p.z());if(!b.getType().name().equals(image.material()) || !(b.getState() instanceof Chest chest))throw new IllegalStateException("Chest changed during goodie transfer recovery");return chest;
    }
    private void finish(Player player,GoodieChestStore.Pending intent)throws SQLException {
        var invalid=storage.query(db->GoodieChestStore.invalidated(db,intent));
        for(var image:intent.images()) {
            var current=chest(image);var contents=current.getBlockInventory().getContents();var before=ItemStack.deserializeItemsFromBytes(image.before());var after=ItemStack.deserializeItemsFromBytes(image.after());var clean=GoodieChestPlan.copy(after);filter(clean,invalid);
            if(!intent.id().equals(current.getPersistentDataContainer().get(chestApplied,PersistentDataType.STRING)) && !Arrays.equals(contents,before) && !Arrays.equals(contents,after) && !Arrays.equals(contents,clean))throw new IllegalStateException("Chest contents changed during transfer recovery");
        }
        var worlds=new LinkedHashSet<World>();
        for(var image:intent.images()) {
            var c=chest(image);var after=ItemStack.deserializeItemsFromBytes(image.after());filter(after,invalid);c.getSnapshotInventory().setContents(after);c.getPersistentDataContainer().set(chestApplied,PersistentDataType.STRING,intent.id());if(!c.update(false,false))throw new IllegalStateException("Unable to restore goodie chest");worlds.add(c.getWorld());
        }
        for(var world:worlds)world.save(true);interrupt("world-saved",intent);
        var inventory=ItemStack.deserializeItemsFromBytes(intent.inventory());var cursor=ItemStack.deserializeItemsFromBytes(intent.cursor());filter(inventory,invalid);filter(cursor,invalid);
        if(player!=null){player.getInventory().setContents(inventory);player.setItemOnCursor(cursor[0]);player.getPersistentDataContainer().set(applied,PersistentDataType.STRING,intent.id());plainCursors.applied(player,intent.id());player.saveData();interrupt("player-saved",intent);}
        // Offline recovery publishes the durable player checkpoint instead of loading
        // playerdata outside Bukkit. NativeTradeService applies it on the next login.
        byte[] plain=!GoodieChestPlan.empty(cursor[0]) && !good(cursor[0])?ItemStack.serializeItemsAsBytes(cursor):null;
        storage.transaction(db->{if(GoodieChestStore.complete(db,intent.id(),plain))TradeStore.saveCheckpoint(db,intent.player().toString(),intent.id(),ItemStack.serializeItemsAsBytes(inventory));return null;});if(intent.cursorToken()!=null && !invalid.contains(intent.cursorToken()))cursorOwners.add(intent.player());else cursorOwners.remove(intent.player());refreshLocks();if(player!=null){recoverPlainDelivery(player);player.updateInventory();}
    }
    private boolean unit(ItemStack item){String id=plugin.nativeItems().id(item);return "u_spy".equals(id)||"u_settler".equals(id);}
    private void validateUnits(Player player,ItemStack[] before,ItemStack[] after)throws SQLException {
        int count=0;for(var item:after)if(unit(item))count+=item.getAmount();if(count>1)throw new IllegalArgumentException("Можно носить только один юнит.");
        for(var item:after)if(unit(item) && Arrays.stream(before).noneMatch(item::equals)) {
            var town=item.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(plugin,"unit-town"),PersistentDataType.LONG);if(town==null)continue;
            var owner=storage.findTown(town).orElse(null);if(owner==null)continue;var resident=storage.findResident(player.getUniqueId()).orElse(null);
            if(resident==null || resident.civId()==null || resident.civId()!=owner.civId())throw new IllegalArgumentException("Этот юнит принадлежит другой цивилизации.");
        }
    }
    private void interrupt(String phase,GoodieChestStore.Pending intent) {
        if(intent.player().toString().equals(System.getProperty("civcraft.test.goodie.chest.player")) && phase.equals(System.getProperty("civcraft.test.goodie.chest.interruption"))){paused=true;throw new IllegalStateException("Injected goodie chest interruption at "+phase);}
    }
    private void restoreCursor(Player player)throws SQLException {
        var saved=storage.query(db->GoodieChestStore.cursor(db,player.getUniqueId()));if(saved==null)return;
        var state=storage.query(db->BonusGoodieStore.token(db,saved.token()));if(state==null || state.revision()!=saved.revision() || state.kind()!=BonusGoodieStore.Kind.PLAYER || !state.holder().equals(player.getUniqueId().toString())){storage.transaction(db->{try(var s=db.prepareStatement("DELETE FROM goodie_chest_cursor_checkpoints WHERE player_uuid=?")){s.setString(1,player.getUniqueId().toString());s.executeUpdate();}return null;});cursorOwners.remove(player.getUniqueId());return;}
        for(var item:player.getInventory().getContents())if(items.matches(item,state))return;if(items.matches(player.getItemOnCursor(),state))return;
        var item=ItemStack.deserializeItemsFromBytes(saved.item())[0];if(GoodieChestPlan.empty(player.getItemOnCursor()))player.setItemOnCursor(item);else{int slot=player.getInventory().firstEmpty();if(slot<0)return;player.getInventory().setItem(slot,item);}player.saveData();
    }
    private void recoverPlainDelivery(Player player)throws SQLException {
        var saved=storage.query(db->GoodieChestStore.plainCursor(db,player.getUniqueId()));if(saved==null)return;
        if(!plainCursors.delivered(player,saved.receipt())){if(saved.clear())plainCursors.clear(player,saved.receipt());else plainCursors.deliver(player,saved.receipt(),saved.item());}
        storage.transaction(db->{GoodieChestStore.deliveredPlainCursor(db,player.getUniqueId(),saved.receipt());return null;});
    }
    public void checkpointCursor(Player player){plainCursors.beforeSave(player);}
    public void shutdown(){plainCursors.shutdown();}
    private void tick() {
        if(paused)return;
        try{for(var intent:storage.query(GoodieChestStore::pending)){var player=Bukkit.getPlayer(intent.player());if(player==null || !player.isDead())try{finish(player,intent);}catch(Exception failure){warn(failure);}}refreshLocks();for(var player:Bukkit.getOnlinePlayers())if(!player.isDead()){recoverPlainDelivery(player);if(cursorOwners.contains(player.getUniqueId()) && !storage.query(db->GoodieDepartureStore.waiting(db,player.getUniqueId())))restoreCursor(player);}checkContainers();}catch(Exception failure){warn(failure);}
    }
    private void checkContainers()throws SQLException {
        if(checkContainers.isEmpty())checkContainers.addAll(storage.query(GoodieChestStore::storedContainers));
        for(int i=0;i<16 && !checkContainers.isEmpty();i++) {
            var p=knownContainers.get(checkContainers.removeFirst());if(p==null)continue;var world=Bukkit.getWorld(p.world());if(world==null || !world.isChunkLoaded(p.x()>>4,p.z()>>4))continue;
            var block=world.getBlockAt(p.x(),p.y(),p.z());try {
                if(block.getState() instanceof Chest)purgeChest(new Half(p,block.getType().name()));else storage.transaction(db->{GoodieChestStore.returnMissing(db,p,Set.of());return null;});
            }catch(IllegalArgumentException waiting){/* Retain ownership until the other journal finishes. */}
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void join(PlayerJoinEvent event){if(paused)return;try{for(var p:storage.query(GoodieChestStore::pending))if(p.player().equals(event.getPlayer().getUniqueId()))finish(event.getPlayer(),p);recoverPlainDelivery(event.getPlayer());restoreCursor(event.getPlayer());plainCursors.restore(event.getPlayer());}catch(Exception failure){warn(failure);event.getPlayer().kick(Component.text("Не удалось восстановить перенос товара через сундук."));}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void open(InventoryOpenEvent event){
        if(frozen.contains(event.getPlayer().getUniqueId()) || locked(event.getInventory())){event.setCancelled(true);return;}
        var holder=event.getInventory().getHolder();if(!(holder instanceof Chest) && !(holder instanceof DoubleChest))return;
        try{for(var part:halves(event.getInventory()))purgeChest(part);}catch(IllegalArgumentException waiting){event.setCancelled(true);}catch(Exception failure){event.setCancelled(true);warn(failure);}
    }
    private void purgeChest(Half half)throws SQLException {
        storage.query(db->{GoodieChestStore.requirePositionsAvailable(db,List.of(half.position()));return null;});
        var world=Bukkit.getWorld(half.position().world());if(world==null || !world.isChunkLoaded(half.position().x()>>4,half.position().z()>>4))return;
        if(!(world.getBlockAt(half.position().x(),half.position().y(),half.position().z()).getState() instanceof Chest chest))return;
        var contents=chest.getBlockInventory().getContents();var seen=new HashSet<String>();boolean changed=false;
        for(int i=0;i<contents.length;i++)if(good(contents[i])) {
            String token=items.token(contents[i]);var state=storage.query(db->BonusGoodieStore.token(db,token));
            if(!items.matches(contents[i],state) || state.kind()!=BonusGoodieStore.Kind.CONTAINER || !state.holder().equals(half.position().holder()) || !seen.add(token)){contents[i]=null;changed=true;}
        }
        if(changed){chest.getSnapshotInventory().setContents(contents);if(!chest.update(false,false))throw new IllegalStateException("Unable to remove invalid chest goods");}
        storage.transaction(db->{GoodieChestStore.returnMissing(db,half.position(),seen);return null;});
    }
    /** Canonical ownership changes before vanilla can create an unregistered block drop. */
    private void evacuate(Block block,UUID actor)throws SQLException {
        if(!(block.getState() instanceof Chest chest))return;var p=position(block);
        storage.query(db->{GoodieChestStore.requirePositionsAvailable(db,List.of(p));return null;});
        var contents=chest.getBlockInventory().getContents();if(Arrays.stream(contents).noneMatch(this::good))return;
        var at=new GoodieTransferStore.Drop(p.world(),p.x()+.5,p.y()+.5,p.z()+.5);
        storage.transaction(db->{var seen=new HashSet<String>();for(var item:contents)if(good(item)) {
            String token=items.token(item);var current=BonusGoodieStore.token(db,token);
            if(items.matches(item,current) && current.kind()==BonusGoodieStore.Kind.CONTAINER && current.holder().equals(p.holder()) && seen.add(token)) {
                GoodieTransferStore.requireOriginUnlocked(db,current.origin());var after=BonusGoodieStore.move(db,current,BonusGoodieStore.Kind.ITEM,UUID.randomUUID().toString(),null);GoodieDepartureStore.queueGround(db,actor,after,at);
            }
        }return null;});
        for(int i=0;i<contents.length;i++)if(good(contents[i]))contents[i]=null;chest.getSnapshotInventory().setContents(contents);chest.update(false,false);
    }
    private boolean reserved(Block block){return locks.contains(position(block));}
    private boolean neighborReserved(Block block){if(reserved(block))return true;for(var face:List.of(BlockFace.NORTH,BlockFace.EAST,BlockFace.SOUTH,BlockFace.WEST))if(reserved(block.getRelative(face)))return true;return false;}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breakChest(BlockBreakEvent event){if(frozen.contains(event.getPlayer().getUniqueId()) || reserved(event.getBlock())){event.setCancelled(true);return;}try{evacuate(event.getBlock(),event.getPlayer().getUniqueId());}catch(Exception failure){event.setCancelled(true);warn(failure);}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void place(BlockPlaceEvent event){if(frozen.contains(event.getPlayer().getUniqueId()) || neighborReserved(event.getBlock()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void move(InventoryMoveItemEvent event){if(good(event.getItem()) || locked(event.getSource()) || locked(event.getDestination()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void pickup(InventoryPickupItemEvent event){if(good(event.getItem().getItemStack()) || locked(event.getInventory()))event.setCancelled(true);}
    private void explosion(List<Block> blocks){var auditActor=new UUID(0,0);for(var it=blocks.iterator();it.hasNext();){var b=it.next();if(reserved(b)){it.remove();continue;}try{evacuate(b,auditActor);}catch(Exception failure){it.remove();warn(failure);}}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explosion(EntityExplodeEvent event){explosion(event.blockList());}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explosion(BlockExplodeEvent event){explosion(event.blockList());}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void burn(BlockBurnEvent event){if(reserved(event.getBlock())){event.setCancelled(true);return;}try{evacuate(event.getBlock(),new UUID(0,0));}catch(Exception failure){event.setCancelled(true);warn(failure);}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void extend(BlockPistonExtendEvent event){if(neighborReserved(event.getBlock()) || event.getBlocks().stream().anyMatch(this::neighborReserved))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void retract(BlockPistonRetractEvent event){if(neighborReserved(event.getBlock()) || event.getBlocks().stream().anyMatch(this::neighborReserved))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void change(EntityChangeBlockEvent event){if(reserved(event.getBlock()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void use(PlayerInteractEvent event){if(frozen.contains(event.getPlayer().getUniqueId()) || event.getClickedBlock()!=null && neighborReserved(event.getClickedBlock()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void entity(PlayerInteractEntityEvent event){if(frozen.contains(event.getPlayer().getUniqueId()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void swap(PlayerSwapHandItemsEvent event){if(frozen.contains(event.getPlayer().getUniqueId()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void consume(PlayerItemConsumeEvent event){if(frozen.contains(event.getPlayer().getUniqueId()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void command(PlayerCommandPreprocessEvent event){if(frozen.contains(event.getPlayer().getUniqueId()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void itemPickup(EntityPickupItemEvent event){if(event.getEntity() instanceof Player player && frozen.contains(player.getUniqueId()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void arrowPickup(PlayerPickupArrowEvent event){if(frozen.contains(event.getPlayer().getUniqueId()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void attack(EntityDamageByEntityEvent event){if(event.getDamager() instanceof Player player && frozen.contains(player.getUniqueId()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void damage(org.bukkit.event.entity.EntityDamageEvent event){if(event.getEntity() instanceof Player player && frozen.contains(player.getUniqueId()))event.setCancelled(true);}
}
