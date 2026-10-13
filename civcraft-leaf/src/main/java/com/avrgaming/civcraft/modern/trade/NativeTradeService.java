package com.avrgaming.civcraft.modern.trade;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.economy.CivCraftEconomyService;
import com.avrgaming.civcraft.modern.items.NativeLegacyItems;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.io.File;
import java.sql.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;

public final class NativeTradeService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final NativeLegacyItems items;
    private final CivCraftEconomyService economy;
    private final MarketCatalog market;
    private final NamespacedKey applied;
    private final Map<String,Double> oreRates=new HashMap<>();
    private final Map<UUID,Long> lastClick=new HashMap<>();
    private record Point(long build,long town,String structure,String action,String argument) {}
    private record BankItem(Material material,String name,double base) {}
    public NativeTradeService(CivCraftModernPlugin plugin,StorageBootstrap storage,NativeLegacyItems items,CivCraftEconomyService economy)throws SQLException {
        this.plugin=plugin;this.storage=storage;this.items=items;this.economy=economy;
        applied=new NamespacedKey(plugin,"last-applied-trade");
        market=new MarketCatalog(new File(plugin.getDataFolder(),"civcraft/data/market.yml"),items);
        var civ=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/civ.yml"));
        for(String kind:List.of("iron","gold","diamond","emerald")) {
            double value=civ.getDouble("ore_rates."+kind);
            if(!Double.isFinite(value) || value<0 || value>1_000_000_000)throw new IllegalArgumentException("Invalid ore rate: "+kind);
            oreRates.put(kind,value);
        }
        storage.transaction(db->{for(var entry:market.list())TradeStore.price(db,entry.ident(),MarketPrice.initial(entry.base(),market.rate()));return null;});
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    private Point point(Block block)throws SQLException {
        if(!(block.getState() instanceof Sign))return null;
        return storage.query(db->{
            try(var query=db.prepareStatement("""
                    SELECT sb.id,sb.town_id,sb.structure_id,tp.action,tp.argument
                    FROM structure_trade_points tp JOIN structure_builds sb ON sb.id=tp.build_id
                    JOIN protected_blocks pb ON pb.build_id=sb.id AND pb.world=tp.world AND pb.x=tp.x AND pb.y=tp.y AND pb.z=tp.z
                    WHERE tp.world=? AND tp.x=? AND tp.y=? AND tp.z=? AND sb.status='COMPLETE'
                    AND sb.structure_id IN ('s_bank','s_market')
                    """)) {
                query.setString(1,block.getWorld().getName());query.setInt(2,block.getX());query.setInt(3,block.getY());query.setInt(4,block.getZ());
                try(var result=query.executeQuery()) {return result.next()?new Point(result.getLong(1),result.getLong(2),result.getString(3),result.getString(4),result.getString(5)):null;}
            }
        });
    }
    public boolean isPublicTradePoint(Block block) {
        try{return point(block)!=null;}catch(SQLException exception){return false;}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void interact(PlayerInteractEvent event) {
        if(event.getHand()!=EquipmentSlot.HAND || event.getClickedBlock()==null)return;
        if(event.getAction()!=Action.RIGHT_CLICK_BLOCK && event.getAction()!=Action.LEFT_CLICK_BLOCK)return;
        Player player=event.getPlayer();
        try {
            var point=point(event.getClickedBlock());if(point==null)return;
            event.setCancelled(true);
            long now=System.currentTimeMillis();
            if(now-lastClick.getOrDefault(player.getUniqueId(),0L)<250)return;
            lastClick.put(player.getUniqueId(),now);
            if(economy.usesExternalProvider())throw new IllegalArgumentException("Для этого обмена требуется внутренний баланс CivCraft.");
            if(!player.getItemOnCursor().getType().isAir())throw new IllegalArgumentException("Закройте инвентарь перед обменом.");
            recover(player);
            if(point.structure().equals("s_bank") && point.action().equals("bank"))bank(player,point);
            else if(point.structure().equals("s_market") && !point.action().equals("bank"))market(player,point);
            render(event.getClickedBlock(),point);
        } catch(IllegalArgumentException exception){player.sendMessage(Component.text(exception.getMessage()));}
        catch(SQLException | RuntimeException exception) {
            plugin.getLogger().severe("Trade failed for "+player.getUniqueId()+": "+exception.getMessage());
            player.kick(Component.text("Обмен приостановлен. Переподключитесь после восстановления сервера."));
        }
    }
    private BankItem bankItem(String action) {
        boolean block=action.endsWith("b");String kind=block?action.substring(0,action.length()-1):action;
        String material=switch(kind){case "iron"->"IRON";case "gold"->"GOLD";case "diamond"->"DIAMOND";case "emerald"->"EMERALD";default->throw new IllegalArgumentException("Неизвестный ресурс банка.");};
        String name=switch(kind){case "iron"->"Железо";case "gold"->"Золото";case "diamond"->"Алмазы";default->"Изумруды";};
        return new BankItem(Material.valueOf(material+(block?"_BLOCK":kind.equals("iron") || kind.equals("gold")?"_INGOT":"")),name,oreRates.get(kind)*(block?9:1));
    }
    private double[] bankSettings(long town)throws SQLException {
        return storage.query(db->{
            try(var query=db.prepareStatement("SELECT bank_level,bank_fee FROM town_trade_settings WHERE town_id=?")) {
                query.setLong(1,town);try(var result=query.executeQuery()){return result.next()?new double[]{result.getInt(1),result.getDouble(2)}:new double[]{1,0.05};}
            }
        });
    }
    private static double exchange(int level) {
        if(level<1 || level>10)throw new IllegalArgumentException("Некорректный уровень банка.");
        return new double[]{0.4,0.5,0.6,0.7,0.8,0.9,1,1.2,1.5,2}[level-1];
    }
    private void bank(Player player,Point point)throws SQLException {
        var resource=bankItem(point.argument());ItemStack hand=player.getInventory().getItemInMainHand();
        if(hand.getType()!=resource.material() || items.id(hand)!=null)throw new IllegalArgumentException("Держите обычный ресурс для обмена: "+resource.name()+".");
        var settings=bankSettings(point.town());
        var resident=storage.findResident(player.getUniqueId()).orElseThrow();
        long total=(long)(resource.base()*hand.getAmount()*exchange((int)settings[0]));
        long fee=resident.townId()!=null && resident.townId()==point.town()?0:Math.round(total*settings[1]);
        if(fee<0 || fee>total)throw new IllegalArgumentException("Некорректная комиссия банка.");
        ItemStack[] after=copy(player.getInventory().getContents());after[player.getInventory().getHeldItemSlot()]=null;
        String id=UUID.randomUUID().toString();
        var receipt=storage.transaction(db->TradeStore.prepare(db,id,player.getUniqueId().toString(),ItemStack.serializeItemsAsBytes(after),0,total-fee,point.town(),fee));
        apply(player,receipt);
        player.sendMessage(Component.text("Обмен: "+hand.getAmount()+" × "+resource.name()+", получено "+(total-fee)+" монет. Комиссия: "+fee+"."));
    }
    private void market(Player player,Point point)throws SQLException {
        var entry=market.require(Integer.parseInt(point.argument()));
        boolean buying=point.action().startsWith("buy");int amount=point.action().endsWith("big")?64:1;
        if(amount>1 && !entry.stackable())throw new IllegalArgumentException("Этот товар продаётся по одному.");
        ItemStack[] after=copy(player.getInventory().getContents());
        if(buying)add(after,entry.exemplar(),amount);
        else remove(after,entry,amount);
        String id=UUID.randomUUID().toString();
        var receipt=storage.transaction(db->{
            var price=TradeStore.price(db,entry.ident(),MarketPrice.initial(entry.base(),market.rate()));
            var quote=price.trade(buying,amount,entry.step(),market.stepCount(),market.rate(),entry.stackable());
            if(quote.coins()>1_000_000_000_000L)throw new IllegalArgumentException("Сумма обмена слишком велика.");
            var pending=TradeStore.prepare(db,id,player.getUniqueId().toString(),ItemStack.serializeItemsAsBytes(after),buying?quote.coins():0,buying?0:quote.coins(),null,0);
            TradeStore.updatePrice(db,entry.ident(),quote.next());return pending;
        });
        apply(player,receipt);
        player.sendMessage(Component.text((buying?"Куплено: ":"Продано: ")+amount+" × "+entry.name()+", "+(buying?receipt.debit():receipt.credit())+" монет."));
        refreshMarket(entry.id());
    }
    private static ItemStack[] copy(ItemStack[] contents) {
        ItemStack[] result=new ItemStack[contents.length];for(int i=0;i<contents.length;i++)result[i]=contents[i]==null?null:contents[i].clone();return result;
    }
    private static void add(ItemStack[] contents,ItemStack exemplar,int quantity) {
        int remaining=quantity;
        for(int slot=0;slot<36 && remaining>0;slot++) {
            ItemStack existing=contents[slot];
            if(existing!=null && existing.isSimilar(exemplar)) {
                int added=Math.min(remaining,Math.max(0,existing.getMaxStackSize()-existing.getAmount()));existing.setAmount(existing.getAmount()+added);remaining-=added;
            }
        }
        for(int slot=0;slot<36 && remaining>0;slot++)if(contents[slot]==null || contents[slot].getType().isAir()) {
            ItemStack stack=exemplar.clone();int added=Math.min(remaining,stack.getMaxStackSize());stack.setAmount(added);contents[slot]=stack;remaining-=added;
        }
        if(remaining>0)throw new IllegalArgumentException("Освободите место в инвентаре.");
    }
    private void remove(ItemStack[] contents,MarketCatalog.Entry entry,int quantity) {
        int remaining=quantity;
        for(int slot=0;slot<36 && remaining>0;slot++) {
            ItemStack stack=contents[slot];if(stack==null)continue;
            boolean matches=entry.customId()==null?items.id(stack)==null && stack.getType()==entry.exemplar().getType():entry.customId().equals(items.id(stack));
            if(matches) {
                int taken=Math.min(stack.getAmount(),remaining);remaining-=taken;
                if(taken==stack.getAmount())contents[slot]=null;else stack.setAmount(stack.getAmount()-taken);
            }
        }
        if(remaining>0)throw new IllegalArgumentException("Недостаточно товара: требуется "+quantity+" × "+entry.name()+".");
    }
    private void apply(Player player,TradeStore.Pending pending)throws SQLException {
        testInterruption("prepared",player);
        if(!pending.id().equals(player.getPersistentDataContainer().get(applied,PersistentDataType.STRING))) {
            restoreInventory(player,pending.id(),pending.inventory());
        }
        testInterruption("player-saved",player);
        storage.transaction(db->TradeStore.finish(db,pending));
    }
    private void restoreInventory(Player player,String id,byte[] inventory) {
        ItemStack[] contents=ItemStack.deserializeItemsFromBytes(inventory);
        if(contents.length!=player.getInventory().getSize())throw new IllegalArgumentException("Saved trade inventory has wrong size");
        player.getInventory().setContents(contents);
        player.getPersistentDataContainer().set(applied,PersistentDataType.STRING,id);
        player.saveData();
    }
    /** Explicit JVM flags are used by the isolated crash-recovery integration tests. */
    private static void testInterruption(String phase,Player player) {
        if(player.getName().equals(System.getProperty("civcraft.test.trade.player"))
                && phase.equals(System.getProperty("civcraft.test.trade.interruption"))) {
            System.clearProperty("civcraft.test.trade.interruption");
            throw new IllegalStateException("Injected trade interruption at "+phase);
        }
    }
    private void recover(Player player)throws SQLException {
        var pending=storage.query(db->TradeStore.pending(db,player.getUniqueId().toString()));
        if(pending!=null)apply(player,pending);
        else {
            var checkpoint=storage.query(db->TradeStore.checkpoint(db,player.getUniqueId().toString()));
            if(checkpoint!=null && !checkpoint.id().equals(player.getPersistentDataContainer().get(applied,PersistentDataType.STRING)))restoreInventory(player,checkpoint.id(),checkpoint.inventory());
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void join(PlayerJoinEvent event) {
        try{recover(event.getPlayer());}
        catch(SQLException | RuntimeException exception){plugin.getLogger().severe("Trade recovery failed: "+exception.getMessage());event.getPlayer().kick(Component.text("Не удалось восстановить обмен. Попробуйте войти позже."));}
    }
    @EventHandler public void chunk(ChunkLoadEvent event) {
        for(var tile:event.getChunk().getTileEntities())if(tile instanceof Sign sign)refresh(sign.getBlock());
    }
    public void refreshBuild(long buildId) {
        try {
            var locations=storage.query(db->{
                List<Location> result=new ArrayList<>();
                try(var query=db.prepareStatement("SELECT world,x,y,z FROM structure_trade_points WHERE build_id=?")) {
                    query.setLong(1,buildId);try(var rows=query.executeQuery()){while(rows.next()){World world=plugin.getServer().getWorld(rows.getString(1));if(world!=null)result.add(new Location(world,rows.getInt(2),rows.getInt(3),rows.getInt(4)));}}
                }
                return result;
            });
            for(var location:locations)if(location.getWorld().isChunkLoaded(location.getBlockX()>>4,location.getBlockZ()>>4))refresh(location.getBlock());
        } catch(SQLException exception){plugin.getLogger().warning("Trade sign refresh failed: "+exception.getMessage());}
    }
    private void refreshMarket(int id) {
        try {
            var locations=storage.query(db->{
                List<Location> result=new ArrayList<>();
                try(var query=db.prepareStatement("SELECT tp.world,tp.x,tp.y,tp.z FROM structure_trade_points tp JOIN structure_builds sb ON sb.id=tp.build_id WHERE sb.status='COMPLETE' AND sb.structure_id='s_market' AND tp.argument=?")) {
                    query.setString(1,String.valueOf(id));try(var rows=query.executeQuery()){while(rows.next()){World world=plugin.getServer().getWorld(rows.getString(1));if(world!=null)result.add(new Location(world,rows.getInt(2),rows.getInt(3),rows.getInt(4)));}}
                }
                return result;
            });
            for(var location:locations)if(location.getWorld().isChunkLoaded(location.getBlockX()>>4,location.getBlockZ()>>4))refresh(location.getBlock());
        } catch(SQLException exception){plugin.getLogger().warning("Market price signs could not refresh: "+exception.getMessage());}
    }
    private void refresh(Block block) {
        try{var point=point(block);if(point!=null)render(block,point);}
        catch(SQLException | IllegalArgumentException exception){plugin.getLogger().warning("Unable to update trade sign: "+exception.getMessage());}
    }
    private void render(Block block,Point point)throws SQLException {
        if(!(block.getState() instanceof Sign sign))return;
        List<String> lines;
        if(point.structure().equals("s_bank") && point.action().equals("bank")) {
            var resource=bankItem(point.argument());var settings=bankSettings(point.town());
            lines=List.of(resource.name(),"Курс: "+Math.round(exchange((int)settings[0])*100)+"%","1 = "+(long)(resource.base()*exchange((int)settings[0]))+" монет","Комиссия: "+Math.round(settings[1]*100)+"%");
        } else if(point.structure().equals("s_market") && !point.action().equals("bank")) {
            var entry=market.find(Integer.parseInt(point.argument())).orElse(null);
            if(entry==null)lines=List.of("Нет товара","","","");
            else {
                boolean buying=point.action().startsWith("buy");int amount=point.action().endsWith("big")?64:1;
                var price=storage.query(db->TradeStore.price(db,entry.ident(),MarketPrice.initial(entry.base(),market.rate())));
                long coins=price.trade(buying,amount,entry.step(),market.stepCount(),market.rate(),entry.stackable()).coins();
                lines=List.of(buying?"Купить":"Продать",entry.name(),coins+" монет",amount+" шт.");
            }
        } else return;
        for(int line=0;line<4;line++)sign.line(line,Component.text(lines.get(line)));sign.setWaxed(true);sign.update(true,false);
    }
}
