package com.avrgaming.civcraft.modern.war;
import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.io.File;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
public final class WarClockService {
    private final CivCraftModernPlugin plugin;private final StorageBootstrap storage;private final WarCalendar calendar;private final boolean casual;
    public WarClockService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;calendar=new WarCalendar(new File(plugin.getDataFolder(),"civcraft/data/war.yml"),ZoneId.systemDefault());
        casual=Boolean.parseBoolean(String.valueOf(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/civ.yml")).get("global.casual_mode",false)));
        storage.transaction(db->{WarClockStore.install(db);return null;});
    }
    public WarCalendar calendar(){return calendar;}
    public void tick() {
        try {
            record Changes(java.util.List<WarClockStore.Event> events,java.util.List<ConquestStore.Result> results) {}
            var changes=storage.transaction(db->{long now=System.currentTimeMillis();var events=WarClockStore.synchronize(db,calendar,now);return new Changes(events,ConquestStore.processClosed(db,casual,now));});
            var events=changes.events();
            for(var event:events)if(!event.state().equals("SKIPPED"))plugin.getServer().broadcast(Component.text(event.state().equals("ACTIVE")?"Боевой период начался. Окончание: "+format(Instant.ofEpochMilli(event.end())):"Боевой период завершён."));
            boolean changed=false;for(var result:changes.results())if(result.towns()>0 || result.civilizations()>0){changed=true;plugin.getServer().broadcast(Component.text(result.casual()?"Итоги войны сохранены. В мирном режиме города остаются у прежних владельцев.":"Итоги войны сохранены: передано городов — "+result.towns()+", побеждено цивилизаций — "+result.civilizations()+"."));}
            if(changed)plugin.governments().refreshQueuedProduction();
        }catch(SQLException failure){plugin.getLogger().severe("War calendar update failed: "+failure.getMessage());}
    }
    private String format(Instant instant){return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm z",Locale.forLanguageTag("ru")).withZone(calendar.zone()).format(instant);}
    public void info(Player player)throws SQLException {
        long now=System.currentTimeMillis();Long end=storage.query(db->WarClockStore.activeEnd(db,now));boolean active=end!=null;
        player.sendMessage(Component.text("Военный календарь: "+(active?"боевой период активен":"боевой период не активен")+". Следующий запуск: "+format(calendar.next(Instant.ofEpochMilli(now)))+". Часовой пояс: "+calendar.zone()+"."));
        if(active)player.sendMessage(Component.text("Окончание текущего периода: "+format(Instant.ofEpochMilli(end))+"."));
        player.sendMessage(Component.text("Период запускается при наличии объявленных войн. Объявление войны и захват ещё разрабатываются."));
    }
}
