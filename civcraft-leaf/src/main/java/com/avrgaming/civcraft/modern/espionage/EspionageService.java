package com.avrgaming.civcraft.modern.espionage;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.trade.*;
import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class EspionageService implements Listener,CommandExecutor {
    private record Offer(String mission,long targetCiv,long expires) {}
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final MissionCatalog catalog;
    private final MissionEffects effects;
    private final YamlConfiguration config;
    private final double scoutRange;
    private final Map<UUID,Offer> offers=new ConcurrentHashMap<>();
    private final Map<UUID,Long> joined=new HashMap<>();
    private final Map<String,String> warnings=new HashMap<>();
    private final Set<UUID> seenSpy=new HashSet<>();
    private final Map<UUID,Long> equipWarnings=new HashMap<>();
    private int ticks;
    public EspionageService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;var file=new File(plugin.getDataFolder(),"civcraft/data/espionage.yml");catalog=new MissionCatalog(file);effects=new MissionEffects(file);config=YamlConfiguration.loadConfiguration(file);
        scoutRange=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/war.yml")).getDouble("scout_tower.range",300);
        if(!Double.isFinite(scoutRange) || scoutRange<0)throw new IllegalArgumentException("Неверная дальность разведки шпиона");
        storage.transaction(db->{EspionageStore.install(db);try(var s=db.prepareStatement("UPDATE spy_missions SET state='ABORTED',result='Сервер был остановлен',finished_at=? WHERE state='ACTIVE'")){s.setLong(1,System.currentTimeMillis());s.executeUpdate();}return null;});
        for(var player:Bukkit.getOnlinePlayers())joined.put(player.getUniqueId(),System.currentTimeMillis());
        plugin.getServer().getPluginManager().registerEvents(this,plugin);Objects.requireNonNull(plugin.getCommand("spy")).setExecutor(this);
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,20L,20L);
    }
    private String id(ItemStack item){return plugin.nativeItems().id(item);}
    private boolean book(ItemStack item){String id=id(item);return id!=null && id.startsWith("spy_");}
    private ItemStack[] snapshot(Player player){var items=player.getInventory().getContents();for(int i=0;i<items.length;i++)if(items[i]!=null)items[i]=items[i].clone();return items;}
    private void ready(Player player)throws SQLException {
        if(plugin.isFoundationPending(player.getUniqueId()) || plugin.isWarCampPending(player.getUniqueId()))throw new IllegalArgumentException("Сначала завершите размещение постройки.");
        if(!player.getItemOnCursor().getType().isAir() || !Set.of(org.bukkit.event.inventory.InventoryType.CRAFTING,org.bukkit.event.inventory.InventoryType.CREATIVE,org.bukkit.event.inventory.InventoryType.PLAYER).contains(player.getOpenInventory().getTopInventory().getType()))throw new IllegalArgumentException("Закройте инвентарь перед действием шпиона.");
        plugin.goodieTransfers().requireUnlocked(player.getUniqueId());plugin.trades().recover(player);
    }
    private void requireSpy(Player player)throws SQLException {
        var resident=storage.findResident(player.getUniqueId()).orElseThrow();if(resident.townId()==null || resident.civId()==null)throw new IllegalArgumentException("Шпиону нужен город цивилизации.");
        for(var item:player.getInventory().getContents())if("u_spy".equals(id(item))) {
            Long town=item.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(plugin,"unit-town"),PersistentDataType.LONG);
            // UnitMaterial.validateUnitUse permits units without a surviving owning town.
            if(town==null)return;
            var owner=storage.findTown(town).orElse(null);if(owner==null || owner.civId()==resident.civId())return;
        }throw new IllegalArgumentException("Нужен юнит шпиона, принадлежащий вашей цивилизации.");
    }
    private void war(Connection db)throws SQLException {
        long now=System.currentTimeMillis();try(var s=db.prepareStatement("SELECT 1 FROM war_sessions WHERE state='ACTIVE' AND start_at<=? AND end_at>?")){s.setLong(1,now);s.setLong(2,now);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Во время боевого периода шпионские миссии запрещены.");}}
    }
    private MissionTargets.Town enemyCulture(Connection db,Player player,long civ)throws SQLException {
        war(db);var target=MissionTargets.town(db,player.getLocation(),false);if(target==null || target.civ()==civ)throw new IllegalArgumentException("Войдите в культурную территорию другой цивилизации.");
        try(var s=db.prepareStatement("SELECT 1 FROM administrative_civilizations WHERE civ_id=?")){s.setLong(1,target.civ());try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Административную цивилизацию нельзя атаковать шпионажем.");}}
        var claimed=MissionTargets.town(db,player.getLocation(),true);if(claimed!=null)try(var s=db.prepareStatement("SELECT 1 FROM administrative_civilizations WHERE civ_id=?")){s.setLong(1,claimed.civ());try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Административную цивилизацию нельзя атаковать шпионажем.");}}
        if(plugin.warClock().casual()){var status=com.avrgaming.civcraft.modern.diplomacy.DiplomacyStore.status(db,civ,target.civ());if(status!=com.avrgaming.civcraft.modern.diplomacy.DiplomacyStore.Status.HOSTILE && status!=com.avrgaming.civcraft.modern.diplomacy.DiplomacyStore.Status.WAR)throw new IllegalArgumentException("В мирном режиме шпионаж требует враждебных отношений или войны.");}return target;
    }
    private int online(long town)throws SQLException {int count=0;for(var player:Bukkit.getOnlinePlayers()){var resident=storage.findResident(player.getUniqueId()).orElse(null);if(resident!=null && resident.townId()!=null && resident.townId()==town)count++;}return count;}
    private void offer(Player player,String id)throws SQLException {
        ready(player);requireSpy(player);var mission=catalog.require(id);if(mission.slot()<=0)throw new IllegalArgumentException("Эта миссия отключена в исходном режиме.");
        if(!player.isOp()) {
            long registered=storage.query(db->{try(var s=db.prepareStatement("SELECT created_at FROM residents WHERE uuid=?")){s.setString(1,player.getUniqueId().toString());try(var r=s.executeQuery()){r.next();return r.getLong(1);}}});long now=System.currentTimeMillis();
            if(now<=registered+config.getLong("espionage.spy_register_time",1440)*60000)throw new IllegalArgumentException("Для шпионажа нужно дождаться установленного срока после регистрации.");
            if(now<=joined.getOrDefault(player.getUniqueId(),now)+config.getLong("espionage.spy_online_time",10)*60000)throw new IllegalArgumentException("Перед шпионажем нужно непрерывно играть не менее "+config.getLong("espionage.spy_online_time",10)+" минут.");
        }
        var resident=storage.findResident(player.getUniqueId()).orElseThrow();var target=storage.query(db->enemyCulture(db,player,resident.civId()));int online=online(target.id());
        offers.put(player.getUniqueId(),new Offer(id,target.civ(),System.currentTimeMillis()+120000));
        player.sendMessage(Component.text(mission.name()+": "+mission.cost()+" монет из казны города, "+mission.seconds()+" сек. Шанс успеха: "+Math.round((1-MissionCatalog.chance(mission.fail(),online,mission.intel()))*100)+"%. Ответьте «да»/«нет» или /spy confirm yes|no."));
    }
    private void confirm(Player player,String answer)throws SQLException {
        var offer=offers.remove(player.getUniqueId());if(offer==null || offer.expires()<System.currentTimeMillis())throw new IllegalArgumentException("Сначала выберите миссию.");
        if(!answer.equalsIgnoreCase("yes") && !answer.equalsIgnoreCase("да")){player.sendMessage(Component.text("Миссия отменена."));return;}
        ready(player);requireSpy(player);var resident=storage.findResident(player.getUniqueId()).orElseThrow();
        storage.transaction(db->{var target=enemyCulture(db,player,resident.civId());if(target.civ()!=offer.targetCiv())throw new IllegalArgumentException("Вы сменили цивилизацию цели.");if(EspionageStore.active(db).stream().anyMatch(run->run.player().equals(player.getUniqueId())))throw new IllegalArgumentException("Миссия уже выполняется.");EspionageStore.start(db,new EspionageStore.Run(UUID.randomUUID().toString(),player.getUniqueId(),resident.townId(),target.id(),target.civ(),offer.mission(),catalog.require(offer.mission()).seconds()),System.currentTimeMillis());return null;});
        player.sendMessage(Component.text("Миссия началась. Оставайтесь на территории цивилизации цели."));
    }
    private ItemStack missionBook(MissionCatalog.Mission mission) {
        var item=new ItemStack(Material.WRITTEN_BOOK);var meta=(BookMeta)item.getItemMeta();meta.displayName(Component.text(mission.name()));meta.title(Component.text(mission.name()));meta.author(Component.text("Шпионская служба"));meta.pages(Component.text(mission.name()+"\nСтоимость: "+mission.cost()+"\nДлительность: "+mission.seconds()+" сек.\nДальность: "+mission.range()+"\nПКМ: начать подготовку миссии."));meta.setMaxStackSize(1);meta.getPersistentDataContainer().set(new NamespacedKey(plugin,"material-id"),PersistentDataType.STRING,mission.id());item.setItemMeta(meta);return item;
    }
    private void equip(Player player)throws SQLException {
        ready(player);requireSpy(player);var inventory=snapshot(player);for(int i=0;i<inventory.length;i++)if(book(inventory[i]))inventory[i]=null;
        for(var mission:catalog.selectable()){int slot=mission.slot();if(inventory[slot]!=null && !inventory[slot].getType().isAir()){int empty=-1;for(int i=9;i<36;i++)if(inventory[i]==null || inventory[i].getType().isAir()){empty=i;break;}if(empty<0)throw new IllegalArgumentException("Освободите место в основном инвентаре для книг миссий.");inventory[empty]=inventory[slot];}inventory[slot]=missionBook(mission);}
        var receipt=storage.transaction(db->TradeStore.prepare(db,UUID.randomUUID().toString(),player.getUniqueId().toString(),ItemStack.serializeItemsAsBytes(inventory),0,0,null,0));plugin.trades().applyPrepared(player,receipt);player.sendMessage(Component.text("Книги миссий размещены на панели быстрого доступа."));
    }
    private void townMessage(long town,String message)throws SQLException {for(var player:Bukkit.getOnlinePlayers()){var resident=storage.findResident(player.getUniqueId()).orElse(null);if(resident!=null && resident.townId()!=null && resident.townId()==town)player.sendMessage(Component.text(message));}}
    private void abort(EspionageStore.Run run,String reason)throws SQLException {storage.transaction(db->{EspionageStore.finish(db,run.id(),"ABORTED",reason,System.currentTimeMillis());return null;});warnings.remove(run.id());var player=Bukkit.getPlayer(run.player());if(player!=null)player.sendMessage(Component.text(reason));}
    private void removeSpy(ItemStack[] inventory){for(int i=0;i<inventory.length;i++)if("u_spy".equals(id(inventory[i])) || book(inventory[i]))inventory[i]=null;}
    private void exposed(Player player,EspionageStore.Run run)throws SQLException {
        ready(player);var inventory=snapshot(player);removeSpy(inventory);var receipt=storage.transaction(db->{EspionageStore.finish(db,run.id(),"FAILED","Шпион раскрыт",System.currentTimeMillis());return TradeStore.prepare(db,UUID.randomUUID().toString(),player.getUniqueId().toString(),ItemStack.serializeItemsAsBytes(inventory),0,0,null,0);});plugin.trades().applyPrepared(player,receipt);Bukkit.broadcast(Component.text("Шпион "+player.getName()+" раскрыт при выполнении миссии «"+catalog.require(run.mission()).name()+"»."));
    }
    private double exposure(Player player,EspionageStore.Run run)throws SQLException {
        var at=player.getLocation();long nearby=Bukkit.getOnlinePlayers().stream().filter(p->!p.getUniqueId().equals(player.getUniqueId()) && p.getWorld()==player.getWorld() && p.getLocation().distanceSquared(at)<600).count();
        double range=scoutRange;
        int scouts=storage.query(db->{int count=0;try(var s=db.prepareStatement("SELECT world,x,y,z,template_path FROM active_structure_builds WHERE town_id=? AND structure_id IN ('s_scouttower','s_scoutship')")){s.setLong(1,run.target());try(var r=s.executeQuery()){while(r.next())if(r.getString(1).equals(at.getWorld().getName())){var offset=com.avrgaming.civcraft.modern.build.LegacyGeometry.center(r.getString(5));double dx=r.getDouble(2)+offset.x()-at.getX(),dy=r.getDouble(3)+offset.y()-at.getY(),dz=r.getDouble(4)+offset.z()-at.getZ();if(dx*dx+dy*dy+dz*dz<range*range)count++;}}}return count;});
        double add=config.getDouble("espionage.exposure_per_second",1)+nearby*config.getDouble("espionage.exposure_per_player",2)+scouts*config.getDouble("espionage.exposure_per_scout",5);
        return storage.transaction(db->EspionageStore.exposure(db,player.getUniqueId(),add));
    }
    private void tick() {
        try {
            offers.values().removeIf(offer->offer.expires()<System.currentTimeMillis());if(++ticks%5==0)storage.transaction(db->{EspionageStore.decay(db);return null;});
            if(ticks%5==0)refreshBooks();
            for(var run:storage.query(EspionageStore::active)){var player=Bukkit.getPlayer(run.player());if(player==null){abort(run,"Шпион вышел с сервера.");continue;}
                try{requireSpy(player);var resident=storage.findResident(player.getUniqueId()).orElseThrow();if(resident.townId()==null || resident.townId()!=run.source())throw new IllegalArgumentException("Шпион сменил город.");var target=storage.query(db->enemyCulture(db,player,resident.civId()));if(target.civ()!=run.targetCiv())throw new IllegalArgumentException("Вы покинули территорию цивилизации цели.");
                    double exposure=exposure(player,run),percent=exposure/1000;
                    if(percent>=config.getDouble("espionage.town_exposure_failure",1.95)){exposed(player,run);continue;}
                    String warning="";if(percent>config.getDouble("espionage.town_exposure_warning",.4))warning="В городе действует вражеский шпион.";
                    if(percent>config.getDouble("espionage.town_exposure_location",.8))warning+=" Координаты: "+player.getLocation().getBlockX()+", "+player.getLocation().getBlockY()+", "+player.getLocation().getBlockZ()+".";
                    if(percent>config.getDouble("espionage.town_exposure_name",.9))warning+=" Имя: "+player.getName()+".";
                    if(!warning.isEmpty() && !warning.equals(warnings.put(run.id(),warning)))townMessage(run.target(),warning);
                    int left=storage.transaction(db->EspionageStore.step(db,run));player.sendActionBar(Component.text("Миссия: "+left+" сек. | Раскрытие: "+Math.round(percent*100)+"%"));if(left==0)perform(player,run);
                }catch(IllegalArgumentException failure){abort(run,failure.getMessage());}
            }
        }catch(SQLException|RuntimeException failure){plugin.getLogger().warning("Espionage operation retained: "+failure.getMessage());}
    }
    private void perform(Player player,EspionageStore.Run run)throws SQLException {
        ready(player);var resident=storage.findResident(player.getUniqueId()).orElseThrow();var target=storage.query(db->MissionTargets.validate(db,player.getLocation(),resident.civId(),catalog.require(run.mission()),catalog));
        if(target.town().civ()!=run.targetCiv())throw new IllegalArgumentException("Цивилизация цели изменилась.");
        var mission=target.mission();int online=online(target.town().id());var goods=storage.townGoodieEffects(run.source());String buff=switch(mission.id()){case "spy_steal_treasury"->"buff_dirty_money";case "spy_poison_granary"->"buff_espionage";case "spy_sabotage","spy_sabotage_wonder"->"buff_sabotage";default->"";};double modifier=goods.buffs().containsKey(buff)?goods.value(buff):1;
        boolean success=ThreadLocalRandom.current().nextInt(100)>=(int)(MissionCatalog.chance(mission.fail(),online,mission.intel())*modifier*100);
        boolean caught=!success && ThreadLocalRandom.current().nextInt(100)<(int)(MissionCatalog.chance(mission.compromise(),online,mission.intel())*100);
        var inventory=snapshot(player);if(caught)removeSpy(inventory);if(success && mission.id().equals("spy_investigate_town")){var report=report(target.town().id());int empty=-1;for(int i=0;i<36;i++)if(inventory[i]==null || inventory[i].getType().isAir()){empty=i;break;}if(empty<0)throw new IllegalArgumentException("Освободите место для доклада разведки.");inventory[empty]=report;}
        record Result(String message,TradeStore.Pending trade,GoodieTransferStore.Pending piracy) {}
        var construction=success && mission.id().equals("spy_sabotage_wonder")?plugin.legacyStructures().snapshotForSabotage(target.building().id()):null;
        if(construction!=null){var world=Bukkit.getWorld(target.building().world());if(world==null)throw new IllegalArgumentException("Мир строящегося чуда недоступен.");world.save(true);}
        var result=storage.transaction(db->{
            var current=MissionTargets.validate(db,player.getLocation(),resident.civId(),catalog.require(run.mission()),catalog);
            if(!current.equals(target))throw new IllegalArgumentException("Цель миссии изменилась.");
            try(var s=db.prepareStatement("UPDATE towns SET coins=coins-? WHERE id=? AND coins>=?")){s.setLong(1,mission.cost());s.setLong(2,run.source());s.setLong(3,mission.cost());if(s.executeUpdate()!=1)throw new IllegalArgumentException("В казне города недостаточно монет для миссии.");}
            String message=success?effects.apply(db,run.source(),target,System.currentTimeMillis(),construction):caught?"Миссия провалена, шпион раскрыт и уничтожен.":"Миссия провалена. Шпион не раскрыт.";
            EspionageStore.finish(db,run.id(),success?"SUCCESS":"FAILED",message,System.currentTimeMillis());byte[] image=ItemStack.serializeItemsAsBytes(inventory);
            if(success && mission.id().equals("spy_pirate"))return new Result(message,null,plugin.goodieTransfers().preparePiracy(db,player,target.building().id(),image,UUID.randomUUID().toString()));
            return new Result(message,TradeStore.prepare(db,UUID.randomUUID().toString(),player.getUniqueId().toString(),image,0,0,null,0),null);
        });if(construction!=null)plugin.legacyStructures().stopSabotagedBuild(target.building().id());
        if(result.piracy()!=null)plugin.goodieTransfers().applyPiracy(player,result.piracy());else plugin.trades().applyPrepared(player,result.trade());
        warnings.remove(run.id());player.sendMessage(Component.text(result.message()));if(caught)Bukkit.broadcast(Component.text("Шпион "+player.getName()+" пойман при миссии «"+mission.name()+"»."));
        if(success && mission.id().equals("spy_subvert_government"))plugin.governments().refreshProduction(target.town().civ());
        if(success && !mission.id().equals("spy_investigate_town"))townMessage(target.town().id(),"Город пострадал от шпионской миссии: "+mission.name()+".");
    }
    private ItemStack report(long town)throws SQLException {
        var city=storage.findTown(town).orElseThrow();var civ=storage.findCiv(city.civId()).orElseThrow();var upkeep=plugin.upkeep().report(town);
        String research=storage.query(db->{try(var s=db.prepareStatement("SELECT tech_id FROM civ_research WHERE civ_id=?")){s.setLong(1,city.civId());try(var r=s.executeQuery()){if(!r.next())return "нет";return plugin.research().findTech(r.getString(1)).map(com.avrgaming.civcraft.modern.research.TechDefinition::name).orElse("неизвестно");}}});
        String time=java.time.ZonedDateTime.now(java.time.ZoneId.of(plugin.getConfig().getString("server.timezone",java.time.ZoneId.systemDefault().getId()))).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z"));
        var book=new ItemStack(Material.WRITTEN_BOOK);var meta=(BookMeta)book.getItemMeta();meta.title(Component.text("Доклад разведки"));meta.author(Component.text("Шпионская служба"));
        meta.pages(Component.text("Город: "+city.name()+"\nЦивилизация: "+civ.name()+"\nВремя: "+time+"\n\nКазна: "+city.coins()+"\nМолотки: "+city.hammersPerHour()+"\nНаука: "+city.beakersPerHour()+"\nРост: "+city.growth()+"\nКультура: "+storage.staticTownCulture(town)+"\nИсследуется: "+research),Component.text("Содержание города\n\nТерритория: "+upkeep.territory()+"\nПостройки: "+upkeep.structures()+"\n\nИтого с бонусами:\n"+upkeep.total()+" монет в день\n\nСчастье: "+city.happiness()));book.setItemMeta(meta);return book;
    }
    private void refreshBooks()throws SQLException {
        for(var player:Bukkit.getOnlinePlayers())refreshBooks(player);
    }
    private void refreshBooks(Player player)throws SQLException {
            boolean spy=Arrays.stream(player.getInventory().getContents()).anyMatch(item->"u_spy".equals(id(item)));
            if(spy){if(!seenSpy.contains(player.getUniqueId())) {
                if(!player.getItemOnCursor().getType().isAir() || !Set.of(org.bukkit.event.inventory.InventoryType.CRAFTING,org.bukkit.event.inventory.InventoryType.CREATIVE,org.bukkit.event.inventory.InventoryType.PLAYER).contains(player.getOpenInventory().getTopInventory().getType()))return;
                try{equip(player);seenSpy.add(player.getUniqueId());equipWarnings.remove(player.getUniqueId());}catch(IllegalArgumentException failure){long now=System.currentTimeMillis();if(now-equipWarnings.getOrDefault(player.getUniqueId(),0L)>=30000){equipWarnings.put(player.getUniqueId(),now);player.sendMessage(Component.text(failure.getMessage()+" Книги можно получить командой /spy equip."));}}
            }}
            else {
                seenSpy.remove(player.getUniqueId());equipWarnings.remove(player.getUniqueId());if(Arrays.stream(player.getInventory().getContents()).noneMatch(this::book))return;
                try{ready(player);}catch(IllegalArgumentException locked){return;}var inventory=snapshot(player);for(int i=0;i<inventory.length;i++)if(book(inventory[i]))inventory[i]=null;
                var receipt=storage.transaction(db->TradeStore.prepare(db,UUID.randomUUID().toString(),player.getUniqueId().toString(),ItemStack.serializeItemsAsBytes(inventory),0,0,null,0));plugin.trades().applyPrepared(player,receipt);
            }
    }
    private void inventoryChanged(Player player) {
        plugin.getServer().getScheduler().runTask(plugin,()->{if(!player.isOnline() || player.isDead())return;try{refreshBooks(player);}catch(SQLException|RuntimeException failure){plugin.getLogger().warning("Spy inventory refresh failed: "+failure.getMessage());}});
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void closeInventory(org.bukkit.event.inventory.InventoryCloseEvent event){if(event.getPlayer() instanceof Player player)inventoryChanged(player);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void unitDrop(org.bukkit.event.player.PlayerDropItemEvent event){if("u_spy".equals(id(event.getItemDrop().getItemStack())))inventoryChanged(event.getPlayer());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void unitPickup(org.bukkit.event.entity.EntityPickupItemEvent event){if(event.getEntity() instanceof Player player && "u_spy".equals(id(event.getItem().getItemStack())))inventoryChanged(player);}
    @EventHandler(priority=EventPriority.HIGHEST) public void death(org.bukkit.event.entity.PlayerDeathEvent event) {
        if(event.getKeepInventory())return;
        boolean spy=Arrays.stream(event.getEntity().getInventory().getContents()).anyMatch(item->"u_spy".equals(id(item)));event.getDrops().removeIf(item->book(item)||"u_spy".equals(id(item)));event.getItemsToKeep().removeIf(item->book(item)||"u_spy".equals(id(item)));
        if(!spy)return;
        try {
            var resident=storage.findResident(event.getEntity().getUniqueId()).orElse(null);if(resident==null || resident.townId()==null)return;long town=resident.townId();
            String history=storage.query(db->{var text=new StringBuilder();try(var s=db.prepareStatement("SELECT m.created_at,r.name,t.name,m.mission,m.state,m.result FROM spy_missions m LEFT JOIN residents r ON r.uuid=m.player LEFT JOIN towns t ON t.id=m.target_town WHERE m.source_town=? AND m.state IN ('SUCCESS','FAILED') ORDER BY m.created_at DESC LIMIT 50")){s.setLong(1,town);try(var r=s.executeQuery()){while(r.next())text.append(java.time.Instant.ofEpochMilli(r.getLong(1))).append("\n").append(r.getString(2)).append(" → ").append(r.getString(3)).append("\n").append(catalog.require(r.getString(4)).name()).append(": ").append(r.getString(6)).append("\n\n");}}return text.toString();});
            var report=new ItemStack(Material.WRITTEN_BOOK);var meta=(BookMeta)report.getItemMeta();meta.title(Component.text("Журнал миссий"));meta.author(Component.text("Шпионская служба"));var pages=new ArrayList<Component>();if(history.isEmpty())history="Завершённых миссий этого города нет.";for(int i=0;i<history.length();i+=800)pages.add(Component.text(history.substring(i,Math.min(history.length(),i+800))));meta.pages(pages);report.setItemMeta(meta);event.getDrops().add(report);
        }catch(SQLException|RuntimeException failure){plugin.getLogger().warning("Unable to create spy mission report: "+failure.getMessage());}
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof Player player)){sender.sendMessage("Команда доступна игроку.");return true;}
        try{if(args.length==0 || args[0].equalsIgnoreCase("list")){for(var m:catalog.selectable())player.sendMessage(Component.text(m.id()+" — "+m.name()+", "+m.cost()+" монет, "+m.seconds()+" сек."));player.sendMessage(Component.text("/spy equip, /spy start <ID>, /spy status, /spy cancel"));}
            else if(args[0].equalsIgnoreCase("equip"))equip(player);
            else if(args.length==2 && args[0].equalsIgnoreCase("start"))offer(player,args[1]);
            else if(args.length==2 && args[0].equalsIgnoreCase("confirm"))confirm(player,args[1]);
            else if(args[0].equalsIgnoreCase("cancel")){offers.remove(player.getUniqueId());for(var run:storage.query(EspionageStore::active))if(run.player().equals(player.getUniqueId()))abort(run,"Миссия отменена без списания стоимости.");}
            else if(args[0].equalsIgnoreCase("status")){var run=storage.query(EspionageStore::active).stream().filter(r->r.player().equals(player.getUniqueId())).findFirst().orElse(null);player.sendMessage(Component.text(run==null?"Активной миссии нет.":catalog.require(run.mission()).name()+": осталось "+run.remaining()+" сек."));}
            else throw new IllegalArgumentException("/spy list|equip|start <ID>|confirm yes|status|cancel");
        }catch(IllegalArgumentException failure){player.sendMessage(Component.text(failure.getMessage()));}catch(SQLException|RuntimeException failure){plugin.getLogger().severe("Spy action failed: "+failure.getMessage());player.kick(Component.text("Действие шпиона приостановлено. Переподключитесь после восстановления сервера."));}return true;
    }
    @EventHandler public void join(PlayerJoinEvent event){joined.put(event.getPlayer().getUniqueId(),System.currentTimeMillis());}
    @EventHandler public void quit(PlayerQuitEvent event){joined.remove(event.getPlayer().getUniqueId());offers.remove(event.getPlayer().getUniqueId());seenSpy.remove(event.getPlayer().getUniqueId());equipWarnings.remove(event.getPlayer().getUniqueId());}
    @EventHandler(priority=EventPriority.HIGH) public void use(PlayerInteractEvent event) {
        if(event.getHand()!=EquipmentSlot.HAND || event.getAction()!=org.bukkit.event.block.Action.RIGHT_CLICK_AIR && event.getAction()!=org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)return;String id=id(event.getItem());if(!"u_spy".equals(id) && !book(event.getItem()))return;event.setCancelled(true);
        try{if("u_spy".equals(id))equip(event.getPlayer());else offer(event.getPlayer(),id);}catch(IllegalArgumentException failure){event.getPlayer().sendMessage(Component.text(failure.getMessage()));}catch(SQLException|RuntimeException failure){plugin.getLogger().severe("Spy item action failed: "+failure.getMessage());event.getPlayer().kick(Component.text("Действие шпиона приостановлено."));}
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void chat(io.papermc.paper.event.player.AsyncChatEvent event) {
        if(!offers.containsKey(event.getPlayer().getUniqueId()))return;String answer=PlainTextComponentSerializer.plainText().serialize(event.message()).trim();if(!Set.of("yes","no","да","нет").contains(answer.toLowerCase(Locale.ROOT)))return;event.setCancelled(true);
        plugin.getServer().getScheduler().runTask(plugin,()->{
            try{confirm(event.getPlayer(),answer);}
            catch(IllegalArgumentException failure){event.getPlayer().sendMessage(Component.text(failure.getMessage()));}
            catch(SQLException|RuntimeException failure){plugin.getLogger().severe("Spy chat confirmation failed: "+failure.getMessage());event.getPlayer().sendMessage(Component.text("Не удалось подтвердить миссию. Подробности находятся в журнале сервера."));}
        });
    }
}
