package com.avrgaming.civcraft.modern.wall;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import java.sql.SQLException;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;

/** Original two-marker interaction, with session identity and disposable tools. */
public final class WallMarkerService implements Listener {
    private static final Set<String> STYLES=Set.of("default","atlantean","arctic","aztec","cultist","egyptian","elven","hell","roman");
    private static final class Session {
        final String request=UUID.randomUUID().toString();
        final String type,style;final WallPlacement.Authority owner;Location first;
        Session(String type,String style,WallPlacement.Authority owner){this.type=type;this.style=style;this.owner=owner;}
    }
    private final CivCraftModernPlugin plugin;
    private final WallCatalog catalog;
    private final WallPlacement placement;
    private final NamespacedKey marker;
    private final Map<UUID,Session> sessions=new HashMap<>();
    public WallMarkerService(CivCraftModernPlugin plugin,WallCatalog catalog) {
        this.plugin=plugin;this.catalog=catalog;placement=new WallPlacement(plugin);marker=new NamespacedKey(plugin,"wall-marker-session");
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        for(var player:Bukkit.getOnlinePlayers())clean(player);
    }
    public boolean hasPending(UUID actor){return sessions.containsKey(actor);}
    public static List<String> styles(){return STYLES.stream().sorted().toList();}
    private String token(ItemStack item){return item==null || !item.hasItemMeta()?null:item.getItemMeta().getPersistentDataContainer().get(marker,PersistentDataType.STRING);}
    private boolean marker(ItemStack item){return token(item)!=null;}
    private void clean(Player player) {
        for(int i=0;i<player.getInventory().getSize();i++)if(marker(player.getInventory().getItem(i)))player.getInventory().setItem(i,null);
        if(marker(player.getItemOnCursor()))player.setItemOnCursor(null);
    }
    private void later(Player player){plugin.getServer().getScheduler().runTask(plugin,()->clean(player));}
    private void cancel(Player player,boolean notify) {
        var session=sessions.remove(player.getUniqueId());clean(player);if(session==null || !notify)return;
        try {
            var existing=plugin.storage().query(db->WallStore.request(db,session.request,player.getUniqueId()));
            player.sendMessage(existing==null?"Разметка стены отменена.":"Стена #"+existing+" уже передана в строительство. Маркеры убраны.");
        }catch(SQLException failure){plugin.getLogger().warning("Cannot resolve closed wall marker session: "+failure.getMessage());player.sendMessage("Маркеры стены убраны.");}
    }
    public boolean command(Player player,String[] args)throws SQLException {
        if(args.length<2)return false;String action=args[1].toLowerCase(Locale.ROOT);
        if(action.equals("cancel") && hasPending(player.getUniqueId())){cancel(player,true);return true;}
        int offset=action.equals("structure")?2:1;if(args.length<=offset)return false;
        String type=switch(args[offset].toLowerCase(Locale.ROOT)) {
            case "wall","ti_wall" -> "ti_wall";
            case "fortifiedwall","fortified_wall","ti_fortifiedwall" -> "ti_fortifiedwall";
            default -> null;
        };
        if(type==null)return false;
        if(args.length>offset+2)throw new IllegalArgumentException("Использование: /b "+type+" [стиль]. Высоту задают маркеры.");
        String style=args.length==offset+2?args[offset+1].toLowerCase(Locale.ROOT):plugin.settings().legacyDefaultTheme();
        if(args.length==offset+2 && !STYLES.contains(style))throw new IllegalArgumentException("Стили стены: default, atlantean, arctic, aztec, cultist, egyptian, elven, hell, roman.");
        var profile=catalog.profile(type);var owner=plugin.storage().query(db->WallPlacement.authority(db,player.getUniqueId(),profile,true));
        if(hasPending(player.getUniqueId()))throw new IllegalArgumentException("Сначала завершите разметку стены или отмените её: /b cancel.");
        if(plugin.isFoundationPending(player.getUniqueId()) || plugin.isWarCampPending(player.getUniqueId()) || plugin.legacyStructures().hasPreview(player)
                || plugin.roads()!=null && plugin.roads().hasPending(player.getUniqueId()))throw new IllegalArgumentException("Сначала завершите размещение другой постройки.");
        plugin.goodieTransfers().requireUnlocked(player.getUniqueId());plugin.trades().recover(player);
        if(!Set.of(InventoryType.CRAFTING,InventoryType.CREATIVE,InventoryType.PLAYER).contains(player.getOpenInventory().getTopInventory().getType()))throw new IllegalArgumentException("Закройте открытый инвентарь перед разметкой стены.");
        if(!player.getInventory().getItemInMainHand().getType().isAir() || !player.getItemOnCursor().getType().isAir())throw new IllegalArgumentException("Освободите основную руку и курсор для двух маркеров стены.");
        if(player.getInventory().getHeldItemSlot()==8)throw new IllegalArgumentException("Выберите свободный слот 1–8: последний слот используется книгой цивилизации.");
        clean(player);var session=new Session(type,style,owner);var item=new ItemStack(Material.REDSTONE_TORCH,2);var meta=item.getItemMeta();
        meta.displayName(Component.text(profile.fortified()?"Маркер крепостной стены":"Маркер стены"));meta.getPersistentDataContainer().set(marker,PersistentDataType.STRING,session.request);item.setItemMeta(meta);
        sessions.put(player.getUniqueId(),session);player.getInventory().setItemInMainHand(item);
        player.sendMessage("Отметьте две точки стены кликами по блокам в культуре своей цивилизации. Предел: "+profile.maximumSegment()+" блоков. /b cancel — отмена.");
        return true;
    }
    @EventHandler(priority=EventPriority.HIGH)
    public void select(PlayerInteractEvent event) {
        var player=event.getPlayer();var session=sessions.get(player.getUniqueId());boolean tool=marker(event.getItem());
        if(tool && (session==null || event.getHand()!=EquipmentSlot.HAND)){event.setCancelled(true);if(session==null)clean(player);return;}
        if(session==null || event.getHand()!=EquipmentSlot.HAND || event.getClickedBlock()==null || !Set.of(Action.LEFT_CLICK_BLOCK,Action.RIGHT_CLICK_BLOCK).contains(event.getAction()))return;
        boolean denied=event.useInteractedBlock()==Event.Result.DENY;event.setCancelled(true);if(denied)return;
        if(!session.request.equals(token(player.getInventory().getItemInMainHand()))){cancel(player,true);return;}
        try {
            var point=(event.getBlockFace()==BlockFace.UP?event.getClickedBlock().getRelative(BlockFace.UP):event.getClickedBlock()).getLocation();
            var owner=placement.marker(player,point,catalog.profile(session.type));
            if(!owner.equals(session.owner)){cancel(player,true);throw new IllegalArgumentException("Город или цивилизация изменились. Начните разметку заново.");}
            if(session.first==null){session.first=point;player.getInventory().getItemInMainHand().setAmount(1);player.sendMessage("Первая точка стены отмечена. Укажите вторую.");return;}
            long build=plugin.walls().build(session.request,player,session.first,point,session.type,session.style);
            submitted(player,build);
        }catch(IllegalArgumentException rejection){player.sendMessage(rejection.getMessage());}
        catch(SQLException|RuntimeException failure) {
            // Commit may have succeeded even when the response did not reach this handler.
            try{var existing=plugin.storage().query(db->WallStore.request(db,session.request,player.getUniqueId()));if(existing!=null){submitted(player,existing);return;}}
            catch(SQLException lookup){failure.addSuppressed(lookup);}
            plugin.getLogger().severe("Wall marker submission retained for retry: "+failure.getMessage());
            player.sendMessage("Не удалось начать строительство. Повторите второй клик; деньги второй раз не спишутся.");
        }
    }
    private void submitted(Player player,long build){cancel(player,false);player.sendMessage("Строительство стены #"+build+" поставлено в очередь.");}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void place(BlockPlaceEvent e){if(marker(e.getItemInHand()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void click(InventoryClickEvent e){
        if(e.getWhoClicked() instanceof Player p && (hasPending(p.getUniqueId()) || marker(e.getCurrentItem()) || marker(e.getCursor()))){e.setCancelled(true);cancel(p,true);later(p);}
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void drag(InventoryDragEvent e){
        if(e.getWhoClicked() instanceof Player p && (hasPending(p.getUniqueId()) || marker(e.getOldCursor()) || e.getNewItems().values().stream().anyMatch(this::marker))){e.setCancelled(true);cancel(p,true);later(p);}
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void swap(PlayerSwapHandItemsEvent e){if(hasPending(e.getPlayer().getUniqueId()) || marker(e.getMainHandItem()) || marker(e.getOffHandItem())){e.setCancelled(true);cancel(e.getPlayer(),true);later(e.getPlayer());}}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void drop(PlayerDropItemEvent e){if(hasPending(e.getPlayer().getUniqueId()) || marker(e.getItemDrop().getItemStack())){e.setCancelled(true);cancel(e.getPlayer(),true);later(e.getPlayer());}}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void held(PlayerItemHeldEvent e){if(hasPending(e.getPlayer().getUniqueId()))cancel(e.getPlayer(),true);}
    @EventHandler(priority=EventPriority.LOWEST) public void join(PlayerJoinEvent e){clean(e.getPlayer());}
    @EventHandler(priority=EventPriority.LOWEST) public void quit(PlayerQuitEvent e){cancel(e.getPlayer(),false);}
    @EventHandler(priority=EventPriority.LOWEST) public void death(PlayerDeathEvent e){e.getDrops().removeIf(this::marker);cancel(e.getEntity(),false);}
    @EventHandler(priority=EventPriority.LOWEST) public void respawn(PlayerRespawnEvent e){later(e.getPlayer());}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void pickup(EntityPickupItemEvent e){if(marker(e.getItem().getItemStack())){e.setCancelled(true);e.getItem().remove();}}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void hopper(InventoryPickupItemEvent e){if(marker(e.getItem().getItemStack())){e.setCancelled(true);e.getItem().remove();}}
    public void close(){for(var player:Bukkit.getOnlinePlayers())cancel(player,false);sessions.clear();}
}
