package com.avrgaming.civcraft.modern.espionage;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

public final class MissionCatalog {
    public record Mission(String id,String name,long cost,double range,double fail,double compromise,int intel,int seconds,int slot) {}
    private final Map<String,Mission> missions=new LinkedHashMap<>();
    public MissionCatalog(File file) {
        for(var row:YamlConfiguration.loadConfiguration(file).getMapList("missions")) {
            String id=Objects.toString(row.get("id"));
            String name=switch(id){case "spy_investigate_town"->"Разведка города";case "spy_steal_treasury"->"Кража казны";case "spy_incite_riots"->"Провокация бунта";case "spy_subvert_government"->"Подрыв правительства";case "spy_poison_granary"->"Отравление амбара";case "spy_pirate"->"Пиратство";case "spy_sabotage"->"Саботаж здания";case "spy_sabotage_wonder"->"Саботаж чуда";default->throw new IllegalArgumentException("Unknown legacy mission: "+id);};
            double cost=number(row,"cost",0),range=number(row,"range",0),fail=number(row,"fail_chance",0),compromise=number(row,"compromise_chance",0);
            int intel=(int)number(row,"intel",0),seconds=(int)number(row,"length",120),slot=(int)number(row,"slot",-1);
            if(cost<0 || cost!=Math.rint(cost) || cost>1_000_000_000_000L || range<0 || fail<0 || fail>1 || compromise<0 || compromise>1 || intel<0 || seconds<1 || seconds>86400)throw new IllegalArgumentException("Invalid mission: "+id);
            missions.put(id,new Mission(id,name,(long)cost,range,fail,compromise,intel,seconds,slot));
        }
    }
    private static double number(Map<?,?> row,String key,double fallback){double value=row.get(key) instanceof Number n?n.doubleValue():fallback;if(!Double.isFinite(value))throw new IllegalArgumentException("Invalid mission "+key);return value;}
    public Mission require(String id){var mission=missions.get(id);if(mission==null)throw new IllegalArgumentException("Неизвестная миссия: "+id);return mission;}
    public List<Mission> selectable(){return missions.values().stream().filter(m->m.slot()>0).toList();}
    /** Both failure and compromise use the same online intelligence factor in the original. */
    public static double chance(double base,int online,int intel){return 1-(1-base)*(intel==0?1:Math.min(1,(double)Math.max(0,online)/intel));}
}
