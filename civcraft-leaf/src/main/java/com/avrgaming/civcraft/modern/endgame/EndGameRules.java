package com.avrgaming.civcraft.modern.endgame;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import com.avrgaming.civcraft.modern.culture.CultureLevels;

public final class EndGameRules {
    public record Condition(String id,int days,Map<String,String> values) {
        public int integer(String key){return Integer.parseInt(required(key));}
        public double decimal(String key){double value=Double.parseDouble(required(key));if(!Double.isFinite(value))throw new IllegalArgumentException("Invalid victory value: "+key);return value;}
        public String required(String key){return Objects.requireNonNull(values.get(key),"Missing victory value: "+key);}
    }
    private final List<Condition> conditions;
    private final CultureLevels culture;
    public EndGameRules(File civ,File cultureFile) {
        var rows=YamlConfiguration.loadConfiguration(civ).getMapList("end_conditions");
        var configured=new LinkedHashMap<String,Condition>();
        for(var row:rows) {
            var values=new LinkedHashMap<String,String>();row.forEach((key,value)->values.put(key.toString(),Objects.toString(value)));
            String id=values.get("id");int days=Integer.parseInt(values.get("days_held"));
            if(!Set.of("end_culture","end_conquest","end_science","end_diplomacy").contains(id) || days<1 || days>36500 || configured.put(id,new Condition(id,days,Map.copyOf(values)))!=null)throw new IllegalArgumentException("Invalid victory condition: "+id);
        }
        if(configured.size()!=4)throw new IllegalArgumentException("All four victory conditions must be configured");
        conditions=List.copyOf(configured.values());
        culture=new CultureLevels(YamlConfiguration.loadConfiguration(cultureFile).getMapList("culture_levels"));
        if(condition("end_culture").integer("culture_level")<0 || condition("end_culture").integer("towns")<1 || condition("end_culture").integer("wonders")<0)throw new IllegalArgumentException("Invalid cultural victory requirements");
        var conquest=condition("end_conquest");
        for(String field:List.of("percent_captured","percent_captured_with_wonder")){double rate=conquest.decimal(field);if(rate<0 || rate>1)throw new IllegalArgumentException("Invalid conquest fraction");}
        if(conquest.integer("days_after_start")<0 || conquest.integer("days_after_start")>36500 || voteCooldownHours()<0 || voteCooldownHours()>87600 || scienceTech().isBlank())throw new IllegalArgumentException("Invalid victory configuration");
    }
    public List<Condition> conditions(){return conditions;}
    public Condition condition(String id){return conditions.stream().filter(c->c.id().equals(id)).findFirst().orElseThrow();}
    public int cultureLevel(long accumulated){return culture.forCulture(accumulated).number();}
    public int voteCooldownHours(){return condition("end_diplomacy").integer("vote_cooldown_hours");}
    public String scienceTech(){return condition("end_science").required("tech");}
    public static String name(String id){return switch(id){case "end_culture"->"Культурная";case "end_conquest"->"Завоевательная";case "end_science"->"Научная";case "end_diplomacy"->"Дипломатическая";default->id;};}
}
