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
    private final CivCraftModernPlugin plugin;private final StorageBootstrap storage;private final WarCalendar calendar;
    public WarClockService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;calendar=new WarCalendar(new File(plugin.getDataFolder(),"civcraft/data/war.yml"),ZoneId.systemDefault());
        storage.transaction(db->{WarClockStore.install(db);return null;});
    }
    public WarCalendar calendar(){return calendar;}
    public void tick() {
        try {
            var events=storage.transaction(db->WarClockStore.synchronize(db,calendar,System.currentTimeMillis()));
            for(var event:events)if(!event.state().equals("SKIPPED"))plugin.getServer().broadcast(Component.text(event.state().equals("ACTIVE")?"Боевой период начался. Окончание: "+format(Instant.ofEpochMilli(event.end())):"Боевой период завершён."));
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
