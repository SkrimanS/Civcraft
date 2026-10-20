package com.avrgaming.civcraft.modern.war;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.trade.TradeStore;
import java.sql.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;

/** Deployable cannons, controls, persistent flights and recoverable world mutations. */
public final class SiegeCannonService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final SiegeCannonCatalog catalog;
    private final SiegeCannonPlacement placement;
    private final SiegeCannonImpact impacts;
    private final NamespacedKey visual,playerHit;
    private final Set<Firework> effects=new HashSet<>();
    private SiegeWorldStore.Job working;
    private List<SiegeWorldStore.Cell> changes;
    private int index,ticks;
    private long lastWarning;
    public SiegeCannonService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException{
        this.plugin=plugin;this.storage=storage;catalog=SiegeCannonCatalog.load(plugin.getDataFolder().toPath().resolve("civcraft/data/war.yml").toFile());
        placement=new SiegeCannonPlacement(plugin,storage,catalog);impacts=new SiegeCannonImpact(plugin,storage,catalog);
        visual=new NamespacedKey(plugin,"siege-cannon-visual");playerHit=new NamespacedKey(plugin,"siege-cannon-hit");
        storage.transaction(db->{SiegeCannonStore.install(db);SiegeWorldStore.install(db);SiegeCannonImpactStore.install(db);return null;});
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,1L,1L);
    }
    private void warn(Exception failure){long now=System.currentTimeMillis();if(now-lastWarning>=30000){lastWarning=now;plugin.getLogger().severe("Siege cannon operation retained for recovery: "+failure.getMessage());}}
    private SiegeWorldStore.Owned owned(Location location)throws SQLException{return storage.query(db->SiegeWorldStore.owned(db,location.getWorld().getName(),location.getBlockX(),location.getBlockY(),location.getBlockZ()));}
    private boolean explosionLocked(Location location){
        try{return storage.query(db->SiegeWorldStore.locked(db,location.getWorld().getName(),location.getBlockX(),location.getBlockY(),location.getBlockZ())
                || SiegeWorldStore.retainedContainer(db,location.getWorld().getName(),location.getBlockX(),location.getBlockY(),location.getBlockZ(),System.currentTimeMillis()));}
        catch(SQLException failure){warn(failure);return true;}
    }
    private boolean guarded(Location location){
        if(location==null)return false;
        try{return storage.query(db->{if(SiegeWorldStore.locked(db,location.getWorld().getName(),location.getBlockX(),location.getBlockY(),location.getBlockZ()) || SiegeWorldStore.retainedContainer(db,location.getWorld().getName(),location.getBlockX(),location.getBlockY(),location.getBlockZ(),System.currentTimeMillis()))return true;
            var cell=SiegeWorldStore.owned(db,location.getWorld().getName(),location.getBlockX(),location.getBlockY(),location.getBlockZ());return cell!=null && (!cell.state().equals("ACTIVE") || !cell.role().equals("air"));});
        }catch(SQLException failure){warn(failure);return true;}
    }
    private void ready(Player player)throws SQLException{
        if(plugin.isFoundationPending(player.getUniqueId()) || plugin.isWarCampPending(player.getUniqueId()))throw new IllegalArgumentException("Сначала завершите размещение другой постройки.");
        if(!player.getItemOnCursor().getType().isAir() || !Set.of(InventoryType.CRAFTING,InventoryType.CREATIVE,InventoryType.PLAYER).contains(player.getOpenInventory().getTopInventory().getType()))throw new IllegalArgumentException("Закройте инвентарь перед использованием пушки.");
        plugin.goodieTransfers().requireUnlocked(player.getUniqueId());plugin.trades().recover(player);
    }
    @EventHandler(priority=EventPriority.HIGH)
    public void use(PlayerInteractEvent event){
        if(event.getHand()!=EquipmentSlot.HAND || !Set.of(Action.LEFT_CLICK_BLOCK,Action.RIGHT_CLICK_BLOCK,Action.RIGHT_CLICK_AIR).contains(event.getAction()))return;
        var player=event.getPlayer();boolean cannonItem="mat_build_cannon".equals(plugin.nativeItems().id(event.getItem()));
        try{
            var cell=event.getClickedBlock()==null?null:owned(event.getClickedBlock().getLocation());
            if(cell!=null && Set.of("fire","angle","power").contains(cell.role())){
                event.setCancelled(true);ready(player);var cannon=storage.query(db->SiegeCannonStore.authorize(db,cell.cannon(),player.getUniqueId(),System.currentTimeMillis()));boolean right=event.getAction()==Action.RIGHT_CLICK_BLOCK;
                if(!cell.role().equals("fire"))storage.transaction(db->SiegeCannonStore.adjust(db,cell.cannon(),player.getUniqueId(),cell.role().equals("angle"),right,System.currentTimeMillis()));
                else if(cannon.ammunition()>=catalog.tnt()){
                    storage.transaction(db->SiegeCannonStore.fire(db,cell.cannon(),UUID.randomUUID().toString(),player.getUniqueId(),catalog,System.currentTimeMillis()));player.sendMessage(Component.text("Пушка: огонь!"));
                }else if(!right){
                    if(player.getInventory().getItemInMainHand().getType()!=Material.TNT)throw new IllegalArgumentException("Для зарядки держите TNT и нажмите ЛКМ по табличке огня.");
                    var after=snapshot(player);for(int i=0;i<36;i++)if(after[i]!=null && after[i].getType()==Material.TNT){if(after[i].getAmount()==1)after[i]=null;else after[i].setAmount(after[i].getAmount()-1);break;}
                    String receipt=UUID.randomUUID().toString();var pending=storage.transaction(db->{SiegeCannonStore.reserveLoad(db,cell.cannon(),receipt,player.getUniqueId(),ItemStack.serializeItemsAsBytes(after),catalog,System.currentTimeMillis());return TradeStore.pending(db,player.getUniqueId().toString());});
                    plugin.trades().applyPrepared(player,pending);storage.transaction(db->SiegeCannonStore.finishLoad(db,receipt));player.sendMessage(Component.text("В пушку добавлен один TNT."));
                }
                paint(storage.query(db->SiegeCannonStore.cannon(db,cell.cannon())));return;
            }
            if(!cannonItem || event.getAction()==Action.LEFT_CLICK_BLOCK)return;
            event.setCancelled(true);ready(player);if(!"mat_build_cannon".equals(plugin.nativeItems().id(player.getInventory().getItemInMainHand())))throw new IllegalArgumentException("Предмет пушки должен оставаться в основной руке.");
            var plan=placement.plan(player);var after=snapshot(player);after[player.getInventory().getHeldItemSlot()]=null;
            var receipt=storage.transaction(db->{SiegeCannonStore.register(db,plan.draft(),catalog,System.currentTimeMillis());SiegeWorldStore.prepare(db,plan.draft().id(),plan.draft().war(),plan.draft().world(),"PLACE",plan.cells(),true);return TradeStore.prepare(db,plan.draft().id(),player.getUniqueId().toString(),ItemStack.serializeItemsAsBytes(after),0,0,null,0);});
            plugin.trades().applyPrepared(player,receipt);player.sendMessage(Component.text("Осадная пушка размещается. ЛКМ/ПКМ по табличкам меняют наведение; ЛКМ с TNT заряжает пушку."));
        }catch(IllegalArgumentException failure){if(cannonItem)event.setCancelled(true);player.sendMessage(Component.text(failure.getMessage()));}
        catch(Exception failure){if(cannonItem)event.setCancelled(true);warn(failure);player.kick(Component.text("Операция с пушкой приостановлена. Переподключитесь для восстановления."));}
    }
    private ItemStack[] snapshot(Player player){return Arrays.stream(player.getInventory().getContents()).map(item->item==null?null:item.clone()).toArray(ItemStack[]::new);}
    private boolean guarded(Inventory inventory){
        if(inventory instanceof DoubleChestInventory chest)return guarded(chest.getLeftSide().getLocation()) || guarded(chest.getRightSide().getLocation());
        return guarded(inventory.getLocation());
    }
    private void tick(){
        try{
            if(ticks++%20==0){
                effects.removeIf(entity->!entity.isValid());
                storage.transaction(db->{SiegeCannonStore.finishLoads(db);SiegeWorldStore.expire(db,System.currentTimeMillis());return null;});
                for(var cannon:storage.query(SiegeCannonStore::cannons)){
                    if(cannon.state().equals("DESTROY_PENDING"))destroy(cannon);else if(cannon.state().equals("ACTIVE"))paint(cannon);
                }
            }
            worldJob();
            for(var shot:storage.query(SiegeCannonStore::shots))flight(shot);
            playerHits();
        }catch(SQLException|RuntimeException failure){warn(failure);}
    }
    private void worldJob()throws SQLException{
        if(working!=null && !working.kind().equals("RESTORE") && !storage.query(db->SiegeWorldStore.active(db,working.war(),System.currentTimeMillis())))storage.transaction(db->{SiegeWorldStore.expire(db,System.currentTimeMillis());return null;});
        if(working!=null){var fresh=storage.query(db->SiegeWorldStore.job(db,working.id()));if(fresh==null || !fresh.state().equals("PREPARED")){working=null;changes=null;}}
        if(working==null)for(var job:storage.query(SiegeWorldStore::pending)){
            if(!job.kind().equals("RESTORE") && !storage.query(db->SiegeWorldStore.active(db,job.war(),System.currentTimeMillis()))){storage.transaction(db->{SiegeWorldStore.expire(db,System.currentTimeMillis());return null;});return;}
            if(job.kind().equals("PLACE") && !storage.query(db->SiegeWorldStore.tradeComplete(db,job.id())))continue;
            working=job;changes=storage.query(db->SiegeWorldStore.cells(db,job.id()));index=0;break;
        }
        if(working==null)return;var world=Bukkit.getWorld(working.world());if(world==null)return;
        for(int count=0;count<256 && index<changes.size();count++,index++){
            var c=changes.get(index);var block=world.getBlockAt(c.x(),c.y(),c.z());var data=Bukkit.createBlockData(c.after());
            if(!block.getBlockData().equals(data)){
                if(block.getState() instanceof Container container)container.getInventory().clear();
                block.setBlockData(data,false);
            }
            if(c.afterNbt()!=null)BlockEntitySnapshot.restore(block.getState(),c.afterNbt());
            if(working.kind().equals("IMPACT") && java.util.concurrent.ThreadLocalRandom.current().nextInt(100)>90)fireworks(block.getLocation(),false);
            if(working.kind().equals("DESTROY"))world.spawnParticle(Particle.EXPLOSION,block.getLocation().add(.5,.5,.5),1,0,0,0,0);
        }
        if(index<changes.size())return;
        var done=working;
        if(done.kind().equals("PLACE"))paint(storage.query(db->SiegeCannonStore.cannon(db,done.id())));
        world.save(true);
        storage.transaction(db->{
            SiegeWorldStore.done(db,done.id());
            if(done.kind().equals("PLACE"))SiegeCannonStore.activate(db,done.id());
            if(done.kind().equals("DESTROY"))try(var s=db.prepareStatement("UPDATE siege_cannons SET state='DESTROYED' WHERE id=? AND state='DESTROY_PENDING'")){s.setString(1,done.id().substring("destroy:".length()));s.executeUpdate();}
            return null;
        });
        if(done.kind().equals("PLACE")){
            var cannon=storage.query(db->SiegeCannonStore.cannon(db,done.id()));notifyOwner(cannon,"Осадная пушка готова: "+cannon.draft().origin().x()+", "+cannon.draft().origin().y()+", "+cannon.draft().origin().z()+".");
        }
        working=null;changes=null;index=0;
    }
    private void notifyOwner(SiegeCannonStore.Cannon cannon,String message)throws SQLException{
        var owner=storage.findResident(cannon.draft().owner()).orElse(null);if(owner==null || owner.civId()==null)return;
        for(var player:Bukkit.getOnlinePlayers()){var resident=storage.findResident(player.getUniqueId()).orElse(null);if(resident!=null && Objects.equals(resident.civId(),owner.civId()))player.sendMessage(Component.text(message));}
    }
    private void destroy(SiegeCannonStore.Cannon cannon)throws SQLException{
        String id="destroy:"+cannon.draft().id();if(storage.query(db->SiegeWorldStore.job(db,id))!=null)return;
        var world=Bukkit.getWorld(cannon.draft().world());if(world==null)return;var cells=new ArrayList<SiegeWorldStore.Cell>();
        for(var source:storage.query(db->SiegeWorldStore.cells(db,cannon.draft().id())))if(!source.role().equals("air")){
            var b=world.getBlockAt(source.x(),source.y(),source.z());String after=b.getType()==Material.COAL_BLOCK?"minecraft:gravel":"minecraft:air";
            cells.add(new SiegeWorldStore.Cell(b.getX(),b.getY(),b.getZ(),b.getBlockData().getAsString(),BlockEntitySnapshot.read(b.getState()),after,null,"rubble"));
        }
        storage.transaction(db->{SiegeWorldStore.prepare(db,id,cannon.draft().war(),cannon.draft().world(),"DESTROY",cells,true);return null;});
    }
    private void paint(SiegeCannonStore.Cannon cannon)throws SQLException{
        var world=Bukkit.getWorld(cannon.draft().world());if(world==null)return;
        for(var c:storage.query(db->SiegeWorldStore.cells(db,cannon.draft().id())))if(Set.of("fire","angle","power").contains(c.role())){
            if(!world.isChunkLoaded(c.x()>>4,c.z()>>4))continue;var block=world.getBlockAt(c.x(),c.y(),c.z());if(!(block.getState() instanceof Sign sign))continue;
            List<String> lines;
            if(c.role().equals("angle"))lines=List.of("Поворот",Integer.toString(cannon.yaw()),cannon.yaw()>0?"→":cannon.yaw()<0?"←":"","ЛКМ − / ПКМ +");
            else if(c.role().equals("power"))lines=List.of("Подъём",Integer.toString(cannon.pitch()),"ЛКМ − / ПКМ +","");
            else {long seconds=Math.max(0,(cannon.cooldownUntil()-System.currentTimeMillis()+999)/1000);lines=List.of("Огонь",cannon.ammunition()+" / "+catalog.tnt()+" TNT",seconds>0?"Ожидание: "+seconds+" с":cannon.ammunition()>=catalog.tnt()?"Нажмите: огонь":"ЛКМ: зарядить","");}
            for(var side:Side.values())for(int i=0;i<4;i++)sign.getSide(side).line(i,Component.text(lines.get(i)));sign.setWaxed(true);sign.update(true,false);
        }
    }
    private void flight(SiegeCannonStore.Shot shot)throws SQLException{
        var cannon=storage.query(db->SiegeCannonStore.cannon(db,shot.cannon()));if(!storage.query(db->SiegeWorldStore.active(db,cannon.draft().war(),System.currentTimeMillis())))return;
        var world=Bukkit.getWorld(shot.world());if(world==null)return;
        if(shot.state().equals("IMPACT_PENDING")){
            impacts.prepare(shot,cannon,world);var job=storage.query(db->SiegeWorldStore.job(db,shot.id()));
            if(job!=null && job.state().equals("DONE"))storage.transaction(db->{try(var s=db.prepareStatement("UPDATE siege_cannon_shots SET state='DONE' WHERE id=?")){s.setString(1,shot.id());s.executeUpdate();}return null;});return;
        }
        var step=SiegeCannonRules.advance(shot.position(),shot.direction());var p=step.position();
        boolean collision=p.y()<world.getMinHeight() || p.y()>=world.getMaxHeight();
        if(!collision)collision=!world.getBlockAt((int)Math.floor(p.x()),(int)Math.floor(p.y()),(int)Math.floor(p.z())).getType().isAir();
        boolean impact=collision || SiegeCannonRules.beyondRange(cannon.draft().muzzle(),p,catalog.maximumRange());
        storage.transaction(db->{SiegeCannonStore.advance(db,shot,step,impact);return null;});
        world.createExplosion(new Location(world,p.x(),p.y(),p.z()),0,false,false);
    }
    private void playerHits()throws SQLException{
        record Hit(String shot,UUID player,double damage){}var hits=storage.query(db->{var result=new ArrayList<Hit>();
            try(var s=db.createStatement();var r=s.executeQuery("SELECT h.id,h.player_uuid,h.damage FROM siege_player_hits h JOIN siege_cannon_shots s ON s.id=h.id JOIN siege_world_jobs j ON j.id=h.id WHERE h.state='PREPARED' AND j.state='DONE' AND s.state<>'CANCELLED'")){while(r.next())result.add(new Hit(r.getString(1),UUID.fromString(r.getString(2)),r.getDouble(3)));}return result;});
        for(var hit:hits){var player=Bukkit.getPlayer(hit.player());if(player==null)continue;
            try{plugin.goodieTransfers().requireUnlocked(player.getUniqueId());}catch(IllegalArgumentException pending){continue;}
            plugin.trades().recover(player);
            if(!hit.shot().equals(player.getPersistentDataContainer().get(playerHit,PersistentDataType.STRING))){player.damage(hit.damage());player.getPersistentDataContainer().set(playerHit,PersistentDataType.STRING,hit.shot());player.saveData();if(player.isDead())Bukkit.broadcast(Component.text(player.getName()+" погиб от выстрела осадной пушки."));}
            storage.transaction(db->{try(var s=db.prepareStatement("UPDATE siege_player_hits SET state='DONE' WHERE id=? AND player_uuid=?")){s.setString(1,hit.shot());s.setString(2,hit.player().toString());s.executeUpdate();}return null;});
        }
    }
    private void fireworks(Location location,boolean red){
        for(int i=0;i<3;i++){var entity=location.getWorld().spawn(location,Firework.class);entity.getPersistentDataContainer().set(visual,PersistentDataType.BYTE,(byte)1);var meta=entity.getFireworkMeta();meta.addEffect(FireworkEffect.builder().withColor(red?Color.RED:Color.ORANGE,Color.YELLOW).flicker(true).with(FireworkEffect.Type.BURST).build());meta.setPower(0);entity.setFireworkMeta(meta);effects.add(entity);plugin.getServer().getScheduler().runTask(plugin,entity::detonate);}
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    public void breakBlock(BlockBreakEvent event){
        try{var cell=owned(event.getBlock().getLocation());if(cell==null || cell.role().equals("air")){if(guarded(event.getBlock().getLocation()))event.setCancelled(true);return;}
            event.setCancelled(true);if(!cell.role().equals("block"))return;
            int hp=storage.transaction(db->SiegeCannonStore.hit(db,cell.cannon(),UUID.randomUUID().toString(),event.getPlayer().getUniqueId(),System.currentTimeMillis()));event.getPlayer().sendMessage(Component.text(hp==0?"Осадная пушка уничтожена.":"Пушка: "+hp+" / "+catalog.health()+" HP."));
            notifyOwner(storage.query(db->SiegeCannonStore.cannon(db,cell.cannon())),hp==0?"Наша осадная пушка уничтожена.":"Наша осадная пушка под атакой: "+hp+" / "+catalog.health()+" HP.");
        }catch(IllegalArgumentException failure){event.getPlayer().sendMessage(Component.text(failure.getMessage()));}catch(SQLException failure){event.setCancelled(true);warn(failure);}
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void place(BlockPlaceEvent e){if(guarded(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void physics(BlockPhysicsEvent e){if(guarded(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void fluids(BlockFromToEvent e){if(guarded(e.getBlock().getLocation()) || guarded(e.getToBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void burn(BlockBurnEvent e){if(guarded(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void ignite(BlockIgniteEvent e){if(guarded(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void change(EntityChangeBlockEvent e){if(guarded(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(EntityExplodeEvent e){if(e.blockList().stream().anyMatch(b->explosionLocked(b.getLocation())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(BlockExplodeEvent e){if(e.blockList().stream().anyMatch(b->explosionLocked(b.getLocation())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void open(InventoryOpenEvent e){if(guarded(e.getInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void click(InventoryClickEvent e){if(guarded(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void drag(InventoryDragEvent e){if(guarded(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void move(InventoryMoveItemEvent e){if(guarded(e.getSource()) || guarded(e.getDestination()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void sign(SignChangeEvent e){if(guarded(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void multiple(BlockMultiPlaceEvent e){if(e.getReplacedBlockStates().stream().anyMatch(b->guarded(b.getLocation())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void grow(BlockGrowEvent e){if(guarded(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void form(BlockFormEvent e){if(guarded(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void spread(BlockSpreadEvent e){if(guarded(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void structure(org.bukkit.event.world.StructureGrowEvent e){if(e.getBlocks().stream().anyMatch(b->guarded(b.getLocation())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void piston(BlockPistonExtendEvent e){if(e.getBlocks().stream().anyMatch(b->guarded(b.getLocation()) || guarded(b.getRelative(e.getDirection()).getLocation())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void piston(BlockPistonRetractEvent e){if(e.getBlocks().stream().anyMatch(b->guarded(b.getLocation()) || guarded(b.getRelative(e.getDirection()).getLocation())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST) public void visual(EntityDamageByEntityEvent e){if(e.getDamager() instanceof Firework f && f.getPersistentDataContainer().has(visual,PersistentDataType.BYTE))e.setCancelled(true);}
    public void close(){effects.forEach(Entity::remove);effects.clear();}
}
