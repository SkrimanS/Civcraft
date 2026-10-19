package com.avrgaming.civcraft.modern.culture;
import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.io.File;
import java.sql.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;

public final class CultureClock {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final long period;
    private final double conversion;
    public CultureClock(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;
        period=Long.getLong("civcraft.test.culture.hourMillis",3600000L);
        if(period<1000 || period>3600000)throw new IllegalArgumentException("Неверный период часов культуры");
        conversion=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/culture.yml")).getDouble("beakers_per_culture",.1);
        if(!Double.isFinite(conversion) || conversion<0)throw new IllegalArgumentException("Неверное преобразование культуры");
        storage.transaction(db->{CultureClockStore.install(db);CultureClockStore.start(db,period,System.currentTimeMillis()/period);return null;});
    }
    public void minute() {
        try{storage.transaction(db->{
            var ids=new LinkedHashMap<Long,Long>();try(var s=db.createStatement();var r=s.executeQuery("SELECT t.id,t.civ_id FROM towns t WHERE NOT EXISTS(SELECT 1 FROM civ_research cr WHERE cr.civ_id=t.civ_id)")){while(r.next())ids.put(r.getLong(1),r.getLong(2));}
            var scientific=new HashSet<Long>();
            for(var entry:ids.entrySet()) {
                long id=entry.getKey(),civ=entry.getValue();
                if(com.avrgaming.civcraft.modern.research.CivilizationScienceStore.victoryActive(db,civ)) {
                    if(scientific.add(civ))com.avrgaming.civcraft.modern.research.CivilizationScienceStore.extra(db,civ,plugin.research().effectiveSciencePerHour(civ)/60);
                } else CultureClockStore.science(db,id,storage.findTown(id).orElseThrow().beakersPerHour()/60);
            }
            return null;
        });}catch(SQLException failure){plugin.getLogger().warning("Unused culture science failed: "+failure.getMessage());}
    }
    public void tick() {
        try {
            var changes=storage.transaction(db->{
                long hour=System.currentTimeMillis()/period;
                if(!CultureClockStore.due(db,period,hour))return Map.<Long,Long>of();
                record Base(long town,String government,double unused) {}
                var bases=new ArrayList<Base>();
                try(var s=db.createStatement();var r=s.executeQuery("""
                    SELECT t.id,c.government,COALESCE(cs.unused_beakers,0) FROM towns t JOIN civilizations c ON c.id=t.civ_id
                    LEFT JOIN culture_science cs ON cs.town_id=t.id WHERE EXISTS(SELECT 1 FROM structure_builds sb
                    WHERE sb.town_id=t.id AND sb.structure_id IN ('s_townhall','s_capitol') AND sb.status IN ('QUEUED','BUILDING','COMPLETE'))
                    """)){while(r.next())bases.add(new Base(r.getLong(1),r.getString(2),r.getDouble(3)));}
                var plans=new ArrayList<CultureClockStore.Production>();
                for(var base:bases) {
                    double raw=storage.staticTownCulture(base.town());
                    double rate=plugin.governments().cultureRate(plugin.governments().townGovernment(base.town()))*storage.findTown(base.town()).orElseThrow().cultureMultiplier();
                    rate+=storage.townWonderBonuses(base.town()).culture();
                    rate+=storage.townGoodieEffects(base.town()).culture();
                    plans.add(new CultureClockStore.Production(base.town(),Math.max(0,raw*rate),base.unused()));
                }
                return CultureClockStore.hourly(db,period,hour,plans,conversion);
            });
            if(!changes.isEmpty()) {
                for(var player:plugin.getServer().getOnlinePlayers()) {
                    var resident=storage.findResident(player.getUniqueId()).orElse(null);
                    if(resident!=null && resident.townId()!=null && changes.containsKey(resident.townId()))player.sendMessage(Component.text("Город получил "+changes.get(resident.townId())+" культуры за час."));
                }
                plugin.culture().tick();
            }
        }catch(SQLException failure){plugin.getLogger().warning("Hourly culture failed: "+failure.getMessage());}
    }
}
