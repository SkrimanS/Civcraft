package com.avrgaming.civcraft.modern.town;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.io.File;
import java.sql.*;
import java.util.*;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;

/** One paid training slot per barracks, progressing in server seconds, never during downtime. */
public final class BarracksService implements org.bukkit.event.Listener {
    private record Unit(String id,String name,long cost,double hammers,String technology,String structure,String upgrade,int item,int data) {}
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final List<Unit> units=new ArrayList<>();
    public BarracksService(CivCraftModernPlugin plugin,StorageBootstrap storage) {
        this.plugin=plugin;this.storage=storage;
        for(var row:YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/units.yml")).getMapList("units")) {
            String id=text(row.get("id"));if(!Set.of("u_settler","u_spy").contains(id))throw new IllegalArgumentException("Unknown legacy unit: "+id);
            double cost=((Number)row.get("cost")).doubleValue(),hammers=((Number)row.get("hammer_cost")).doubleValue();
            if(!Double.isFinite(cost) || cost<0 || cost!=Math.rint(cost) || cost>1_000_000_000_000L || !Double.isFinite(hammers) || hammers<=0)throw new IllegalArgumentException("Invalid unit cost");
            units.add(new Unit(id,text(row.get("name")),(long)cost,hammers,text(row.get("require_tech")),text(row.get("require_struct")),text(row.get("require_upgrade")),((Number)row.get("item_id")).intValue(),((Number)row.get("item_data")).intValue()));
        }
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,20L,20L);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.LOWEST)
    public void preventVanillaSpyUse(org.bukkit.event.player.PlayerInteractEvent event) {
        if("u_spy".equals(plugin.nativeItems().id(event.getItem())) && (event.getAction()==org.bukkit.event.block.Action.RIGHT_CLICK_AIR || event.getAction()==org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK))event.setCancelled(true);
    }
    private static String text(Object value){return Objects.toString(value,"").trim();}
    private List<Unit> available(Connection db,long town)throws SQLException {
        var result=new ArrayList<Unit>();
        for(var unit:units) {
            boolean allowed=true;
            for(String id:unit.technology().split("[,;\\s]+"))if(!id.isBlank())try(var s=db.prepareStatement("SELECT 1 FROM towns t JOIN civ_technologies c ON c.civ_id=t.civ_id WHERE t.id=? AND c.tech_id=?")){s.setLong(1,town);s.setString(2,id);try(var r=s.executeQuery()){if(!r.next())allowed=false;}}
            for(String id:unit.structure().split("[,;\\s]+"))if(!id.isBlank())try(var s=db.prepareStatement("SELECT 1 FROM structure_builds WHERE town_id=? AND structure_id=? AND status='COMPLETE'")){s.setLong(1,town);s.setString(2,id);try(var r=s.executeQuery()){if(!r.next())allowed=false;}}
            for(String id:unit.upgrade().split("[,;\\s]+"))if(!id.isBlank())try(var s=db.prepareStatement("SELECT 1 FROM town_upgrades WHERE town_id=? AND upgrade_id=?")){s.setLong(1,town);s.setString(2,id);try(var r=s.executeQuery()){if(!r.next())allowed=false;}}
            if(allowed)result.add(unit);
        }return result;
    }
    private Unit selected(Connection db,long build,long town)throws SQLException {
        var available=available(db,town);if(available.isEmpty())return null;
        try(var s=db.prepareStatement("SELECT unit FROM barracks_selection WHERE build_id=?")){s.setLong(1,build);try(var r=s.executeQuery()){if(r.next())for(var unit:available)if(unit.id().equals(r.getString(1)))return unit;}}
        return available.getFirst();
    }
    private long price(Connection db,Unit unit,long civ)throws SQLException {return unit.id().equals("u_settler")?Math.multiplyExact(unit.cost(),Math.addExact(1,BarracksStore.settlers(db,civ))):unit.cost();}
    public void action(Player player,long build,long town,String action)throws SQLException {
        if(action.equals("barracks_info") || action.equals("barracks_progress")){var job=storage.query(db->BarracksStore.job(db,build));player.sendMessage(Component.text(job==null || job.state().equals("COMPLETE")?"Казарма свободна.":"Обучение: "+name(job.unit())+", "+Math.round(job.progress()/job.required()*100)+"%."));return;}
        if(action.equals("barracks_prev") || action.equals("barracks_next")) {
            storage.transaction(db->{var available=available(db,town);var selected=selected(db,build,town);if(selected==null)return null;int index=available.indexOf(selected)+(action.equals("barracks_prev")?-1:1);if(index<0 || index>=available.size())return null;
                try(var s=db.prepareStatement("INSERT INTO barracks_selection VALUES(?,?) ON CONFLICT(build_id) DO UPDATE SET unit=excluded.unit")){s.setLong(1,build);s.setString(2,available.get(index).id());s.executeUpdate();}return null;});
            plugin.trades().refreshBuild(build);return;
        }
        if(!action.equals("barracks_train"))throw new IllegalArgumentException("Неизвестное действие казармы.");
        var city=storage.findTown(town).orElseThrow();
        String trained=storage.transaction(db->{
            try(var s=db.prepareStatement("SELECT 1 FROM towns t WHERE t.id=? AND (t.mayor_uuid=? OR EXISTS(SELECT 1 FROM town_assistants a WHERE a.town_id=t.id AND a.player_uuid=?))")){s.setLong(1,town);s.setString(2,player.getUniqueId().toString());s.setString(3,player.getUniqueId().toString());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Обучение начинают мэр и помощники города.");}}
            var unit=selected(db,build,town);if(unit==null)throw new IllegalArgumentException("Нет доступных юнитов.");
            if(unit.id().equals("u_settler"))try(var s=db.prepareStatement("SELECT 1 FROM civilizations c WHERE c.id=? AND (c.leader_uuid=? OR EXISTS(SELECT 1 FROM civ_advisors a WHERE a.civ_id=c.id AND a.resident_uuid=?))")){s.setLong(1,city.civId());s.setString(2,player.getUniqueId().toString());s.setString(3,player.getUniqueId().toString());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Поселенцев обучают лидер и советники цивилизации с правами управления городом.");}}
            BarracksStore.start(db,build,town,city.civId(),unit.id(),unit.hammers(),price(db,unit,city.civId()),city.hammersPerHour()/3600);return unit.name();
        });player.sendMessage(Component.text("Начато обучение: "+trained+". Стоимость списана из казны города."));plugin.trades().refreshBuild(build);
    }
    private String name(String id){return units.stream().filter(unit->unit.id().equals(id)).findFirst().map(Unit::name).orElse(id);}
    public List<String> lines(long build,long town,String action,int marker)throws SQLException {
        if(action.equals("barracks_prev"))return List.of("Предыдущий","юнит","←","");
        if(action.equals("barracks_next"))return List.of("Следующий","юнит","→","");
        if(action.equals("barracks_train"))return List.of("Начать обучение","Оплата из казны","Один юнит","");
        var job=storage.query(db->BarracksStore.job(db,build));
        if(action.equals("barracks_progress")){if(job==null || job.state().equals("COMPLETE"))return List.of("Казарма свободна","Выберите юнита","","");int percent=(int)Math.min(100,Math.round(job.progress()/job.required()*100));return List.of(name(job.unit()),percent+"%",job.state().equals("DELIVERING")?"Сохраняем выдачу":"["+"=".repeat(percent/10)+"_".repeat(10-percent/10)+"]","");}
        return storage.query(db->{var unit=selected(db,build,town);if(unit==null)return List.of("Нет доступных","юнитов","","");long civ;try(var s=db.prepareStatement("SELECT civ_id FROM towns WHERE id=?")){s.setLong(1,town);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing barracks town");civ=r.getLong(1);}}return List.of(unit.name(),price(db,unit,civ)+" монет",unit.hammers()+" молотков","");});
    }
    private ItemStack item(Unit unit,long town,long civ) {
        var item=plugin.nativeItems().vanilla(unit.item(),unit.data(),1);var meta=item.getItemMeta();meta.displayName(Component.text(unit.name()));meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin,"material-id"),PersistentDataType.STRING,unit.id());
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin,"unit-town"),PersistentDataType.LONG,town);
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin,"unit-civ"),PersistentDataType.LONG,civ);
        var lore=new ArrayList<Component>();lore.add(Component.text("Город происхождения: #"+town));
        if(unit.id().equals("u_settler")){meta.getPersistentDataContainer().set(new NamespacedKey(plugin,"foundation-kind"),PersistentDataType.STRING,"town");meta.getPersistentDataContainer().set(new NamespacedKey(plugin,"soulbound"),PersistentDataType.BYTE,(byte)1);lore.add(Component.text("Основание города своей цивилизации. ПКМ по блоку."));lore.add(Component.text("Не теряется при смерти."));}
        else lore.add(Component.text("Юнит шпиона. Принадлежит городу происхождения."));
        meta.lore(lore);item.setItemMeta(meta);return item;
    }
    private record Training(long build,long town,long civ) {}
    private void tick() {
        try {
            var active=storage.query(db->{var result=new ArrayList<Training>();try(var s=db.createStatement();var r=s.executeQuery("SELECT b.id,b.town_id,t.civ_id FROM barracks_training j JOIN active_structure_builds b ON b.id=j.build_id JOIN towns t ON t.id=b.town_id WHERE j.state='TRAINING'")){while(r.next())result.add(new Training(r.getLong(1),r.getLong(2),r.getLong(3)));}return result;});
            for(var training:active){var city=storage.findTown(training.town()).orElse(null);if(city==null)continue;
                storage.transaction(db->{BarracksStore.advance(db,training.build(),Math.max(0,city.hammersPerHour())/3600);return null;});
                var job=storage.query(db->BarracksStore.job(db,training.build()));
                if(job.progress()>=job.required() && plugin.production()!=null){var unit=units.stream().filter(u->u.id().equals(job.unit())).findFirst().orElseThrow();var result=plugin.production().deliverUnit(training.build(),item(unit,training.town(),training.civ()));if(result==ProductionService.UnitDelivery.FULL)storage.transaction(db->{BarracksStore.full(db,training.build());return null;});}
                plugin.trades().refreshBuild(training.build());
            }
        }catch(SQLException|java.io.IOException|RuntimeException failure){plugin.getLogger().warning("Barracks training paused: "+failure.getMessage());}
    }
}
