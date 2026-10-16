package com.avrgaming.civcraft.modern.trade;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.build.*;
import java.io.*;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;

public final class StableService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final StableCatalog catalog;
    private final NamespacedKey purchase;
    private final NamespacedKey waiting;
    private final Set<Long> indexed=new HashSet<>();
    public StableService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException{
        this.plugin=plugin;this.storage=storage;catalog=new StableCatalog(new File(plugin.getDataFolder(),"civcraft/data/structures.yml"));purchase=new NamespacedKey(plugin,"stable-purchase");waiting=new NamespacedKey(plugin,"stable-waiting");
        storage.transaction(db->{StableStore.install(db);return null;});plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::index,80L,600L);
    }
    private record Building(long id,String world,int x,int y,int z,String path) {}
    private void index(){
        try{
            for(var world:Bukkit.getWorlds())for(var entity:world.getEntities())if(entity instanceof AbstractHorse horse && horse.getPersistentDataContainer().has(waiting,PersistentDataType.BYTE))release(horse);
            var buildings=storage.query(db->{var result=new ArrayList<Building>();try(var s=db.createStatement();var r=s.executeQuery("SELECT id,world,x,y,z,template_path FROM structure_builds WHERE structure_id='s_stable' AND status='COMPLETE'")){while(r.next())result.add(new Building(r.getLong(1),r.getString(2),r.getInt(3),r.getInt(4),r.getInt(5),r.getString(6)));}return result;});
            for(var b:buildings)if(!indexed.contains(b.id())){
                var placements=new ArrayList<BlockPlacement>();for(var block:new LegacyDefTemplateLoader().load(Path.of(b.path())).blocks())if(TradePoint.parse(block.signLines()).filter(p->p.action().startsWith("stable_")).isPresent())placements.add(new BlockPlacement(b.world(),b.x()+block.x(),b.y()+block.y(),b.z()+block.z(),"SIGN",block.signLines()));
                storage.transaction(db->{TradeStore.recordPoints(db,b.id(),placements);return null;});indexed.add(b.id());
                for(var p:placements){var point=TradePoint.parse(p.signLines()).orElseThrow();var world=Bukkit.getWorld(p.world());if(point.action().endsWith("_spawn") && world!=null && world.isChunkLoaded(p.x()>>4,p.z()>>4))hideMarker(world.getBlockAt(p.x(),p.y(),p.z()),b.id());}
                if(plugin.trades()!=null)plugin.trades().refreshBuild(b.id());
            }
        }catch(SQLException|IOException|RuntimeException failure){plugin.getLogger().warning("Unable to index stable markers: "+failure.getMessage());}
    }
    public StableCatalog.Offer selected(long build,int group)throws SQLException{return catalog.offer(group,storage.query(db->StableStore.selection(db,build,group)));}
    public void settings(Player player,String[] args)throws SQLException{
        if(args.length<3 || args.length>4)throw new IllegalArgumentException("/town set stablefee [5–100]");
        var resident=storage.findResident(player.getUniqueId()).orElseThrow();if(resident.townId()==null)throw new IllegalArgumentException("Вы не состоите в городе.");long town=resident.townId();
        storage.transaction(db->{
            try(var s=db.prepareStatement("SELECT 1 FROM towns t WHERE t.id=? AND (t.mayor_uuid=? OR EXISTS(SELECT 1 FROM town_assistants a WHERE a.town_id=t.id AND a.player_uuid=?))")){s.setLong(1,town);s.setString(2,player.getUniqueId().toString());s.setString(3,player.getUniqueId().toString());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Комиссию устанавливают мэр и помощники.");}}
            try(var s=db.prepareStatement("SELECT 1 FROM structure_builds WHERE town_id=? AND structure_id='s_stable' AND status='COMPLETE'")){s.setLong(1,town);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("В городе нет завершённой конюшни.");}}
            if(args.length==4){int percent;try{percent=Integer.parseInt(args[3]);}catch(NumberFormatException invalid){throw new IllegalArgumentException("Укажите целый процент от 5 до 100.");}StableStore.fee(db,town,percent/100.0);}return null;
        });
        for(long build:storage.query(db->{var ids=new ArrayList<Long>();try(var s=db.prepareStatement("SELECT id FROM structure_builds WHERE town_id=? AND structure_id='s_stable' AND status='COMPLETE'")){s.setLong(1,town);try(var r=s.executeQuery()){while(r.next())ids.add(r.getLong(1));}}return ids;}))plugin.trades().refreshBuild(build);
        player.sendMessage(Component.text("Комиссия конюшни для гостей: "+Math.round(storage.query(db->StableStore.fee(db,town))*100)+"%."));
    }
    public void navigate(long build,int group,int delta)throws SQLException{
        int size=catalog.group(group).size();if(size==0)throw new IllegalArgumentException("Отдел конюшни пуст.");
        storage.transaction(db->{StableStore.select(db,build,group,Math.floorMod(StableStore.selection(db,build,group)+delta,size));return null;});
    }
    public List<String> lines(long build,long town,String action,int group)throws SQLException{
        if(action.equals("stable_prev"))return List.of("Предыдущий","товар","←","");
        if(action.equals("stable_next"))return List.of("Следующий","товар","→","");
        var offer=selected(build,group);double fee=storage.query(db->StableStore.fee(db,town));return List.of(offer.name(),"Купить",offer.price()+" монет","Гостям: +"+Math.round(fee*100)+"%");
    }
    public void validate(long build,long town,StableCatalog.Offer offer)throws SQLException{
        boolean complete=storage.query(db->{try(var s=db.prepareStatement("SELECT 1 FROM structure_builds b LEFT JOIN building_combat_health h ON h.build_id=b.id WHERE b.id=? AND b.town_id=? AND b.structure_id='s_stable' AND b.status='COMPLETE' AND COALESCE(h.hitpoints,b.max_hitpoints)>0")){s.setLong(1,build);s.setLong(2,town);try(var r=s.executeQuery()){return r.next();}}});
        if(!complete)throw new IllegalArgumentException("Конюшня не работает или разрушена.");
        if(offer.horse()>=5 || offer.item()>=417 && offer.item()<=419){
            boolean tech=storage.query(db->{try(var s=db.prepareStatement("SELECT 1 FROM towns t JOIN civ_technologies c ON c.civ_id=t.civ_id WHERE t.id=? AND c.tech_id='tech_military_science'")){s.setLong(1,town);try(var r=s.executeQuery()){return r.next();}}});
            if(!tech)throw new IllegalArgumentException("Для этого товара требуется военная наука.");
        }
        if(offer.item()==0 && !catalog.horse(offer.horse()).mule() && storage.townGoodieEffects(town).count("good_horses")==0)throw new IllegalArgumentException("Городу нужен торговый ресурс «Лошади».");
    }
    public void prepare(java.sql.Connection db,String receipt,long build,long town,String owner,StableCatalog.Offer offer)throws SQLException{
        var horse=catalog.horse(offer.horse());String action=horse.mule()?"stable_mule_spawn":"stable_horse_spawn";
        try(var s=db.prepareStatement("SELECT world,x,y,z FROM structure_trade_points WHERE build_id=? AND action=? ORDER BY x,y,z LIMIT 1")){s.setLong(1,build);s.setString(2,action);try(var r=s.executeQuery()){
            if(!r.next())throw new IllegalArgumentException("В шаблоне конюшни отсутствует место выдачи животного.");
            StableStore.prepare(db,receipt,build,town,owner,r.getString(1),r.getDouble(2)+.5,r.getDouble(3),r.getDouble(4)+.5,horse);
        }}
    }
    /** Returns the durable entity UUID; no entity is created for ordinary item receipts. */
    public String fulfil(String receipt)throws SQLException{
        var delivery=storage.query(db->StableStore.delivery(db,receipt));if(delivery==null)return null;
        var world=Bukkit.getWorld(delivery.world());if(world==null)throw new IllegalStateException("Мир конюшни недоступен.");
        var chunk=world.getChunkAt(Math.floorDiv((int)Math.floor(delivery.x()),16),Math.floorDiv((int)Math.floor(delivery.z()),16));AbstractHorse horse=null;
        for(var entity:chunk.getEntities())if(receipt.equals(entity.getPersistentDataContainer().get(purchase,PersistentDataType.STRING))){if(!(entity instanceof AbstractHorse found))throw new IllegalStateException("Stable delivery has an invalid entity");if(horse!=null)throw new IllegalStateException("Duplicate stable delivery");horse=found;}
        if(!chunk.isEntitiesLoaded())throw new IllegalStateException("Дождитесь загрузки конюшни.");
        if(horse==null){
            var location=new Location(world,delivery.x(),delivery.y(),delivery.z());
            if(delivery.mule())horse=world.spawn(location,Mule.class,entity->configure(entity,delivery));
            else horse=world.spawn(location,Horse.class,entity->configure(entity,delivery));
        }
        if(!horse.isValid() || horse.isDead())throw new IllegalStateException("Не удалось выдать животное конюшни.");
        world.save(true);return horse.getUniqueId().toString();
    }
    private void configure(AbstractHorse horse,StableStore.Delivery delivery){
        horse.getPersistentDataContainer().set(waiting,PersistentDataType.BYTE,(byte)1);horse.setAI(false);horse.setGravity(false);horse.setInvulnerable(true);horse.setCollidable(false);
        horse.getPersistentDataContainer().set(purchase,PersistentDataType.STRING,delivery.trade());horse.setPersistent(true);horse.setRemoveWhenFarAway(false);horse.setAdult();horse.setOwner(Bukkit.getOfflinePlayer(UUID.fromString(delivery.owner())));horse.setTamed(true);
        horse.customName(Component.text(delivery.name()));horse.setCustomNameVisible(true);horse.setJumpStrength(delivery.jump());
        var speed=horse.getAttribute(Attribute.MOVEMENT_SPEED);if(speed!=null)speed.setBaseValue(Math.max(.001,speed.getBaseValue()+delivery.speed()));
        var health=horse.getAttribute(Attribute.MAX_HEALTH);if(health==null)throw new IllegalStateException("Horse has no health attribute");health.setBaseValue(delivery.health());horse.setHealth(delivery.health());
        if(horse instanceof Horse normal){normal.setColor(Horse.Color.valueOf(delivery.color()));normal.getInventory().setSaddle(new ItemStack(Material.SADDLE));}
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void use(PlayerInteractEntityEvent event){
        if(!(event.getRightClicked() instanceof Horse || event.getRightClicked() instanceof Mule))return;
        if(event.getRightClicked().getPersistentDataContainer().has(waiting,PersistentDataType.BYTE)){event.setCancelled(true);event.getPlayer().sendMessage(Component.text("Покупка животного ещё восстанавливается."));return;}
        if(!event.getRightClicked().getPersistentDataContainer().has(purchase,PersistentDataType.STRING)){event.setCancelled(true);event.getPlayer().sendMessage(Component.text("Эту лошадь нельзя использовать. Купите животное в конюшне."));}
    }
    public void release(String uuid)throws SQLException{var entity=Bukkit.getEntity(UUID.fromString(uuid));if(entity instanceof AbstractHorse horse)release(horse);}
    private void release(AbstractHorse horse)throws SQLException{
        String id=horse.getPersistentDataContainer().get(purchase,PersistentDataType.STRING);if(id==null || !horse.getPersistentDataContainer().has(waiting,PersistentDataType.BYTE))return;
        boolean pending=storage.query(db->{try(var s=db.prepareStatement("SELECT 1 FROM trade_receipts WHERE id=? AND status='PREPARED'")){s.setString(1,id);try(var r=s.executeQuery()){return r.next();}}});if(pending)return;
        horse.setAI(true);horse.setGravity(true);horse.setInvulnerable(false);horse.setCollidable(true);horse.getPersistentDataContainer().remove(waiting);
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void loaded(EntitiesLoadEvent event){for(var entity:event.getEntities())if(entity instanceof AbstractHorse horse && horse.getPersistentDataContainer().has(waiting,PersistentDataType.BYTE))try{release(horse);}catch(SQLException failure){plugin.getLogger().warning("Unable to release stable delivery: "+failure.getMessage());}}
    private void hideMarker(org.bukkit.block.Block block,long build)throws SQLException{
        boolean owned=storage.query(db->{try(var s=db.prepareStatement("SELECT 1 FROM protected_blocks WHERE world=? AND x=? AND y=? AND z=? AND build_id=?")){s.setString(1,block.getWorld().getName());s.setInt(2,block.getX());s.setInt(3,block.getY());s.setInt(4,block.getZ());s.setLong(5,build);try(var r=s.executeQuery()){return r.next();}}});if(!owned)return;
        if(block.getState() instanceof org.bukkit.block.Sign)block.setType(Material.AIR,false);if(!block.getType().isAir())return;
        storage.transaction(db->{try(var s=db.prepareStatement("UPDATE protected_blocks SET material='AIR' WHERE world=? AND x=? AND y=? AND z=? AND build_id=?")){s.setString(1,block.getWorld().getName());s.setInt(2,block.getX());s.setInt(3,block.getY());s.setInt(4,block.getZ());s.setLong(5,build);s.executeUpdate();}return null;});
    }
    @EventHandler
    public void chunk(ChunkLoadEvent event){try{
        var positions=storage.query(db->{var result=new ArrayList<long[]>();try(var s=db.prepareStatement("SELECT build_id,x,y,z FROM structure_trade_points WHERE world=? AND x>=? AND x<? AND z>=? AND z<? AND action IN ('stable_horse_spawn','stable_mule_spawn')")){s.setString(1,event.getWorld().getName());s.setLong(2,(long)event.getChunk().getX()*16);s.setLong(3,((long)event.getChunk().getX()+1)*16);s.setLong(4,(long)event.getChunk().getZ()*16);s.setLong(5,((long)event.getChunk().getZ()+1)*16);try(var r=s.executeQuery()){while(r.next())result.add(new long[]{r.getLong(1),r.getInt(2),r.getInt(3),r.getInt(4)});}}return result;});
        for(var p:positions)hideMarker(event.getWorld().getBlockAt((int)p[1],(int)p[2],(int)p[3]),p[0]);
    }catch(SQLException failure){plugin.getLogger().warning("Unable to hide stable marker: "+failure.getMessage());}}
}
