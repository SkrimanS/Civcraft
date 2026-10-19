package com.avrgaming.civcraft.modern.road;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.war.*;
import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;

public final class RoadService implements Listener {
    private final CivCraftModernPlugin plugin;private final StorageBootstrap storage;private final RoadCatalog catalog;private final RoadPlacement placement;
    private final NamespacedKey marker,speed;private final boolean chunkSpeeds;
    private final Map<UUID,List<Location>> markers=new HashMap<>();
    private final Map<UUID,Long> started=new HashMap<>();
    private RoadStore.Job working;private List<RoadStore.Cell> changes;private int index;private long lastWarning;
    public RoadService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException{
        this.plugin=plugin;this.storage=storage;catalog=RoadCatalog.load(plugin.getDataFolder().toPath().resolve("civcraft/data/structures.yml").toFile());placement=new RoadPlacement(plugin,storage,catalog);
        marker=new NamespacedKey(plugin,"road-marker");speed=new NamespacedKey(plugin,"road-speed");chunkSpeeds=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("civcraft/data/civ.yml").toFile()).getBoolean("global.speed_check_chunks",true);
        storage.transaction(db->{RoadStore.install(db);return null;});plugin.getServer().getPluginManager().registerEvents(this,plugin);plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,1L,1L);
        for(var player:Bukkit.getOnlinePlayers()){clean(player);movement(player,player.getLocation(),false);}
    }
    private void message(Player player,String text){player.sendMessage(Component.text(text));}
    private void warn(Exception e){long now=System.currentTimeMillis();if(now-lastWarning>30000){lastWarning=now;plugin.getLogger().severe("Road operation retained for recovery: "+e.getMessage());}}
    private boolean marker(ItemStack item){return item!=null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(marker,PersistentDataType.BYTE);}
    private void clean(Player player){for(int i=0;i<player.getInventory().getSize();i++)if(marker(player.getInventory().getItem(i)))player.getInventory().setItem(i,null);if(marker(player.getItemOnCursor()))player.setItemOnCursor(null);}
    private void cancel(Player player){started.remove(player.getUniqueId());if(markers.remove(player.getUniqueId())!=null){clean(player);message(player,"Разметка дороги отменена.");}}
    public boolean command(Player player,String[] args)throws SQLException{
        if(args.length<2)return false;String action=args[1].toLowerCase(Locale.ROOT);
        if(action.equals("ti_road") || action.equals("road") || action.equals("structure") && args.length>=3 && args[2].equalsIgnoreCase("ti_road")){
            storage.query(db->RoadStore.authority(db,player.getUniqueId(),true));
            if(plugin.isFoundationPending(player.getUniqueId()) || plugin.isWarCampPending(player.getUniqueId()) || plugin.legacyStructures().hasPreview(player))throw new IllegalArgumentException("Сначала завершите размещение другой постройки.");
            plugin.goodieTransfers().requireUnlocked(player.getUniqueId());plugin.trades().recover(player);
            if(!player.getInventory().getItemInMainHand().getType().isAir() || !player.getItemOnCursor().getType().isAir())throw new IllegalArgumentException("Освободите основную руку и курсор для двух маркеров дороги.");
            cancel(player);var item=new ItemStack(Material.REDSTONE_TORCH,2);var meta=item.getItemMeta();meta.displayName(Component.text("Маркер дороги"));meta.getPersistentDataContainer().set(marker,PersistentDataType.BYTE,(byte)1);item.setItemMeta(meta);player.getInventory().setItemInMainHand(item);markers.put(player.getUniqueId(),new ArrayList<>());started.put(player.getUniqueId(),System.currentTimeMillis());
            message(player,"Отметьте две точки дороги кликом по блокам. Предел: "+catalog.maximum()+" блоков. /b cancel отменяет разметку.");return true;
        }
        if(action.equals("cancel") && markers.containsKey(player.getUniqueId())){cancel(player);return true;}
        if(action.equals("undo")){
            long town=storage.query(db->RoadStore.authority(db,player.getUniqueId(),false));long id=storage.query(db->{try(var s=db.prepareStatement("SELECT u.build_id FROM road_undo_targets u WHERE u.town_id=? AND u.build_id=(SELECT MAX(b.id) FROM structure_builds b WHERE b.town_id=u.town_id)")){s.setLong(1,town);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Отменить можно только последнюю построенную дорогу; доступной отмены нет.");return r.getLong(1);}}});
            remove(player,id,true);return true;
        }
        var at=at(player.getLocation().clone().subtract(0,1,0));
        if(action.equals("demolishroad")){if(args.length!=3)throw new IllegalArgumentException("/b demolishroad <ID>");long id;try{id=Long.parseLong(args[2]);}catch(NumberFormatException bad){throw new IllegalArgumentException("Укажите ID дороги.");}remove(player,id,false);return true;}
        if(at==null)return false;
        if(action.equals("demolish")){remove(player,at.road().id(),false);return true;}
        if(action.equals("info")){var raid=RoadRules.raid(at.road().built(),catalog.raidHours(),System.currentTimeMillis());message(player,"Дорога #"+at.road().id()+": "+at.road().health()+" / "+at.road().maximum()+" HP, стоимость "+at.road().cost()+". "+(raid.open()?"Окно рейда открыто.":"Следующий рейд: "+java.time.Instant.ofEpochMilli(raid.next()).atZone(java.time.ZoneId.systemDefault())));return true;}
        return false;
    }
    @EventHandler(priority=EventPriority.HIGH)
    public void marker(PlayerInteractEvent event){
        var points=markers.get(event.getPlayer().getUniqueId());if(marker(event.getItem()) && (points==null || event.getHand()!=EquipmentSlot.HAND)){event.setCancelled(true);if(points==null)clean(event.getPlayer());return;}
        if(points==null || event.getHand()!=EquipmentSlot.HAND || event.getClickedBlock()==null || !Set.of(Action.LEFT_CLICK_BLOCK,Action.RIGHT_CLICK_BLOCK).contains(event.getAction()))return;
        event.setCancelled(true);var player=event.getPlayer();
        try{
            if(!marker(player.getInventory().getItemInMainHand())){cancel(player);return;}
            var location=(event.getBlockFace()==BlockFace.UP?event.getClickedBlock().getRelative(BlockFace.UP):event.getClickedBlock()).getLocation();placement.marker(player,location);
            if(points.isEmpty()){points.add(location);player.getInventory().getItemInMainHand().setAmount(1);message(player,"Первая точка отмечена. Поставьте второй маркер.");return;}
            var plan=placement.plan(player,points.getFirst(),location);long cost=RoadRules.cost(plan.route().segments(),catalog.segmentCost());
            long id=storage.transaction(db->RoadStore.prepare(db,player.getUniqueId(),location.getWorld().getName(),RoadPlacement.point(points.getFirst()),plan.route().segments(),cost,catalog.health(),plan.cells(),started.get(player.getUniqueId()),System.currentTimeMillis()));
            markers.remove(player.getUniqueId());started.remove(player.getUniqueId());clean(player);message(player,"Дорога #"+id+" строится: "+plan.route().segments()+" сегментов, списано "+cost+" монет.");
        }catch(IllegalArgumentException e){message(player,e.getMessage());}catch(Exception e){warn(e);cancel(player);message(player,"Операция дороги приостановлена; сохранённые изменения будут восстановлены.");}
    }
    private void remove(Player player,long id,boolean undo)throws SQLException{
        long town=storage.query(db->RoadStore.authority(db,player.getUniqueId(),false));var road=storage.query(db->RoadStore.road(db,id));
        if(road.town()!=town)throw new IllegalArgumentException("Дорога принадлежит другому городу.");if(!road.state().equals("ACTIVE"))throw new IllegalArgumentException("Работа с дорогой уже выполняется или она удалена.");
        var world=Bukkit.getWorld(road.world());if(world==null)throw new IllegalArgumentException("Мир дороги недоступен.");var cells=new ArrayList<RoadStore.Cell>();
        for(var cell:storage.query(db->RoadStore.cells(db,id))){var p=cell.point();Long current=storage.query(db->{try(var s=db.prepareStatement("SELECT build_id FROM protected_blocks WHERE world=? AND x=? AND y=? AND z=?")){s.setString(1,road.world());s.setInt(2,p.x());s.setInt(3,p.y());s.setInt(4,p.z());try(var r=s.executeQuery()){return r.next()?r.getLong(1):null;}}});
            if(current!=null && !Objects.equals(current,cell.sourceBuild()))continue;
            if(storage.query(db->SiegeWorldStore.locked(db,road.world(),p.x(),p.y(),p.z())))throw new IllegalArgumentException("Сначала дождитесь завершения военного изменения местности.");
            var block=world.getBlockAt(p.x(),p.y(),p.z());if(block.getState() instanceof Container container && !container.getInventory().isEmpty())throw new IllegalArgumentException("Освободите контейнер на дороге перед удалением: "+p.x()+", "+p.y()+", "+p.z()+".");
            if(block.getState() instanceof Container)storage.query(db->{com.avrgaming.civcraft.modern.trade.GoodieChestStore.requirePositionsAvailable(db,List.of(new com.avrgaming.civcraft.modern.trade.GoodieChestStore.Position(road.world(),p.x(),p.y(),p.z())));return null;});
            if(undo && restricted(Bukkit.createBlockData(cell.before()).getMaterial().name()))continue;
            cells.add(new RoadStore.Cell(p,cell.before(),cell.nbt(),cell.before(),cell.floor()));
        }
        long refund=undo?road.cost():Math.round(catalog.demolishRefund());String job=UUID.randomUUID().toString();
        storage.transaction(db->{if(RoadStore.authority(db,player.getUniqueId(),false)!=town)throw new IllegalArgumentException("Город изменился.");
            try(var s=db.prepareStatement("UPDATE roads SET state='REMOVING' WHERE build_id=? AND state='ACTIVE'")){s.setLong(1,id);if(s.executeUpdate()!=1)throw new IllegalArgumentException("Состояние дороги изменилось.");}
            try(var s=db.prepareStatement("UPDATE structure_builds SET status='ROAD_REMOVE_PENDING' WHERE id=?")){s.setLong(1,id);s.executeUpdate();}
            try(var s=db.prepareStatement("DELETE FROM road_undo_targets WHERE town_id=? AND build_id=?")){s.setLong(1,town);s.setLong(2,id);s.executeUpdate();}
            RoadStore.job(db,job,id,undo?"UNDO":"DEMOLISH",refund,cells);return null;
        });message(player,"Возврат местности дороги #"+id+" поставлен в очередь. Возврат монет: "+refund+".");
    }
    private boolean restricted(String type){return Set.of("WHEAT","CARROTS","POTATOES","REDSTONE_WIRE","REDSTONE_TORCH","REDSTONE_WALL_TORCH","REPEATER","COMPARATOR","TRIPWIRE","TRIPWIRE_HOOK","SUGAR_CANE","SHORT_GRASS","TALL_GRASS","FERN","LARGE_FERN","POPPY","RED_MUSHROOM","SUNFLOWER","LILAC","ROSE_BUSH","PEONY","CAKE","CACTUS","PISTON","STICKY_PISTON","PISTON_HEAD","MOVING_PISTON","PUMPKIN_STEM","MELON_STEM").contains(type) || type.endsWith("_SAPLING");}
    private void tick(){try{
        if(working==null){var jobs=storage.query(RoadStore::pending);if(jobs.isEmpty())return;working=jobs.getFirst();changes=storage.query(db->RoadStore.images(db,working.id()));index=0;}
        var road=storage.query(db->RoadStore.road(db,working.road()));var world=Bukkit.getWorld(road.world());if(world==null)return;
        for(int n=0;n<256 && index<changes.size();n++,index++){var cell=changes.get(index);var p=cell.point();var block=world.getBlockAt(p.x(),p.y(),p.z());var after=Bukkit.createBlockData(cell.after());if(!block.getBlockData().equals(after)){if(block.getState() instanceof Container c)c.getInventory().clear();block.setBlockData(after,false);}if(cell.nbt()!=null)BlockEntitySnapshot.restore(block.getState(),cell.nbt());}
        if(index<changes.size())return;world.save(true);var job=working;storage.transaction(db->{RoadStore.complete(db,job,System.currentTimeMillis());return null;});working=null;changes=null;index=0;
        for(var player:Bukkit.getOnlinePlayers()){movement(player,player.getLocation(),false);var resident=storage.findResident(player.getUniqueId()).orElse(null);if(resident!=null && resident.townId()!=null && resident.townId()==road.town())message(player,job.kind().equals("BUILD")?"Дорога #"+road.id()+" готова.":"Дорога #"+road.id()+" удалена, местность восстановлена.");}
    }catch(SQLException|RuntimeException e){warn(e);}}
    private RoadStore.At at(Location loc)throws SQLException{return storage.query(db->RoadStore.at(db,loc.getWorld().getName(),loc.getBlockX(),loc.getBlockY(),loc.getBlockZ()));}
    private boolean guarded(Location loc){if(loc==null)return false;try{return storage.query(db->{if(RoadStore.reserved(db,loc.getWorld().getName(),loc.getBlockX(),loc.getBlockY(),loc.getBlockZ()))return true;var at=RoadStore.at(db,loc.getWorld().getName(),loc.getBlockX(),loc.getBlockY(),loc.getBlockZ());return at!=null && (!at.road().state().equals("ACTIVE") || at.floor());});}catch(SQLException e){warn(e);return true;}}
    private boolean occupied(Location loc){if(loc==null)return false;try{return at(loc)!=null || guarded(loc);}catch(SQLException e){warn(e);return true;}}
    private boolean guarded(Inventory inventory){if(inventory instanceof DoubleChestInventory chest)return guarded(chest.getLeftSide().getLocation()) || guarded(chest.getRightSide().getLocation());return guarded(inventory.getLocation());}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    public void breakBlock(BlockBreakEvent event){try{
        var block=event.getBlock();var at=at(block.getLocation());if(at==null || !at.floor()){if(guarded(block.getLocation()))event.setCancelled(true);return;}event.setCancelled(true);
        if(!at.road().state().equals("ACTIVE"))return;long now=System.currentTimeMillis();
        if(storage.query(db->WarClockStore.active(db,now))){String request="road-war:"+UUID.randomUUID();storage.transaction(db->{long war=SiegeCannonStore.currentWar(db,now);SiegeWorldStore.prepare(db,request,war,block.getWorld().getName(),"ROADBREAK",List.of(new SiegeWorldStore.Cell(block.getX(),block.getY(),block.getZ(),block.getBlockData().getAsString(),BlockEntitySnapshot.read(block.getState()),"minecraft:air",null,"road")),true);return null;});return;}
        int damage=com.avrgaming.civcraft.modern.wonder.GreatLibraryEnchantments.structureDamage(event.getPlayer().getInventory().getItemInMainHand(),1);
        int hp=storage.transaction(db->RoadStore.hit(db,UUID.randomUUID().toString(),at.road().id(),event.getPlayer().getUniqueId(),damage,catalog.raidHours(),now));message(event.getPlayer(),"Дорога #"+at.road().id()+": "+hp+" / "+at.road().maximum()+" HP.");
        block.getWorld().playSound(block.getLocation(),Sound.BLOCK_ANVIL_USE,.2f,1);block.getWorld().spawnParticle(Particle.SMOKE,block.getLocation().add(.5,.5,.5),5,.2,.2,.2,0);
    }catch(IllegalArgumentException e){message(event.getPlayer(),e.getMessage());}catch(SQLException e){event.setCancelled(true);warn(e);}}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    public void place(BlockPlaceEvent e){try{var at=at(e.getBlock().getLocation());if(guarded(e.getBlock().getLocation())){e.setCancelled(true);return;}if(at!=null){var resident=storage.findResident(e.getPlayer().getUniqueId()).orElse(null);if(resident==null || resident.civId()==null || resident.civId()!=at.road().civ()){e.setCancelled(true);message(e.getPlayer(),"Нельзя перекрывать три блока над дорогой другой цивилизации.");}}}catch(SQLException failure){e.setCancelled(true);warn(failure);}}
    private void movement(Player player,Location location,boolean horse){
        try{var at=at(location.clone().subtract(0,1,0));boolean on=at!=null && at.road().state().equals("ACTIVE");var attribute=player.getAttribute(Attribute.MOVEMENT_SPEED);if(attribute==null)return;
            var existing=attribute.getModifiers().stream().filter(m->m.getKey().equals(speed)).findFirst().orElse(null);
            boolean riding=player.getVehicle() instanceof AbstractHorse;if(on && !riding){if(existing==null)attribute.addModifier(new AttributeModifier(speed,catalog.playerSpeed()-1,AttributeModifier.Operation.MULTIPLY_SCALAR_1));}else if(existing!=null)attribute.removeModifier(existing);
            if(on && riding && horse){var vehicle=player.getVehicle();var velocity=vehicle.getVelocity();vehicle.setVelocity(velocity.setX(velocity.getX()*catalog.horseSpeed()).setZ(velocity.getZ()*catalog.horseSpeed()));}
        }catch(SQLException|RuntimeException e){warn(e);}
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void moved(PlayerMoveEvent e){var a=e.getFrom();var b=e.getTo();if(a.getBlockX()==b.getBlockX() && a.getBlockY()==b.getBlockY() && a.getBlockZ()==b.getBlockZ())return;if(chunkSpeeds && a.getWorld().equals(b.getWorld()) && (a.getBlockX()>>4)==(b.getBlockX()>>4) && (a.getBlockZ()>>4)==(b.getBlockZ()>>4))return;movement(e.getPlayer(),b,true);}
    @EventHandler public void joined(PlayerJoinEvent e){clean(e.getPlayer());movement(e.getPlayer(),e.getPlayer().getLocation(),false);}
    @EventHandler public void quit(PlayerQuitEvent e){cancel(e.getPlayer());removeSpeed(e.getPlayer());}
    private void removeSpeed(Player player){var attribute=player.getAttribute(Attribute.MOVEMENT_SPEED);if(attribute!=null)for(var m:new ArrayList<>(attribute.getModifiers()))if(m.getKey().equals(speed))attribute.removeModifier(m);}
    @EventHandler public void held(PlayerItemHeldEvent e){cancel(e.getPlayer());}
    @EventHandler public void swap(PlayerSwapHandItemsEvent e){if(markers.containsKey(e.getPlayer().getUniqueId())){e.setCancelled(true);cancel(e.getPlayer());}}
    @EventHandler public void drop(PlayerDropItemEvent e){if(markers.containsKey(e.getPlayer().getUniqueId()) || marker(e.getItemDrop().getItemStack())){e.setCancelled(true);cancel(e.getPlayer());plugin.getServer().getScheduler().runTask(plugin,()->clean(e.getPlayer()));}}
    @EventHandler(priority=EventPriority.HIGHEST) public void death(PlayerDeathEvent e){cancel(e.getEntity());e.getDrops().removeIf(this::marker);e.getItemsToKeep().removeIf(this::marker);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void spawn(ItemSpawnEvent e){if(marker(e.getEntity().getItemStack()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void pickup(EntityPickupItemEvent e){if(marker(e.getItem().getItemStack())){e.setCancelled(true);e.getItem().remove();}}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void physics(BlockPhysicsEvent e){if(occupied(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void burn(BlockBurnEvent e){if(occupied(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void ignite(BlockIgniteEvent e){for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++){var b=e.getBlock();int by=b.getY()+y;if(by<b.getWorld().getMinHeight() || by>=b.getWorld().getMaxHeight())continue;if(occupied(b.getRelative(x,y,z).getLocation())){e.setCancelled(true);return;}}}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void fluid(BlockFromToEvent e){if(occupied(e.getToBlock().getLocation()) || occupied(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void entity(EntityChangeBlockEvent e){if(occupied(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void explosion(EntityExplodeEvent e){try{for(var b:e.blockList())if(at(b.getLocation())!=null || guarded(b.getLocation())){e.setCancelled(true);return;}}catch(SQLException failure){e.setCancelled(true);warn(failure);}}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void explosion(BlockExplodeEvent e){try{for(var b:e.blockList())if(at(b.getLocation())!=null || guarded(b.getLocation())){e.setCancelled(true);return;}}catch(SQLException failure){e.setCancelled(true);warn(failure);}}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void inventory(InventoryOpenEvent e){if(guarded(e.getInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void click(InventoryClickEvent e){if(e.getWhoClicked() instanceof Player p && markers.containsKey(p.getUniqueId())){e.setCancelled(true);cancel(p);plugin.getServer().getScheduler().runTask(plugin,()->clean(p));}else if(guarded(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void drag(InventoryDragEvent e){if(e.getWhoClicked() instanceof Player p && markers.containsKey(p.getUniqueId())){e.setCancelled(true);cancel(p);}else if(guarded(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void transfer(InventoryMoveItemEvent e){if(guarded(e.getSource()) || guarded(e.getDestination()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void piston(BlockPistonExtendEvent e){if(e.getBlocks().stream().anyMatch(b->occupied(b.getLocation()) || occupied(b.getRelative(e.getDirection()).getLocation())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void piston(BlockPistonRetractEvent e){if(e.getBlocks().stream().anyMatch(b->occupied(b.getLocation()) || occupied(b.getRelative(e.getDirection()).getLocation())))e.setCancelled(true);}
    public void close(){for(var player:Bukkit.getOnlinePlayers()){cancel(player);removeSpeed(player);}}
}
