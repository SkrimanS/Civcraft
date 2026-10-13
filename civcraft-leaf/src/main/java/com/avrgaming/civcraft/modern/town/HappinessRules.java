package com.avrgaming.civcraft.modern.town;
import java.io.File;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

public final class HappinessRules {
    public record State(int level,String name,double threshold,double hammers,double science,double coins,double culture) {}
    public record Result(double happy,double unhappy,double percentage,State state) {}
    private final Map<Integer,Double> bases=new HashMap<>();
    private final List<State> states;
    private final double resident,town,captured,distanceBase,distanceTouching,distanceSeparate,distanceMaximum;
    public HappinessRules(File file) {
        var yaml=YamlConfiguration.loadConfiguration(file);
        resident=value(yaml.getDouble("happiness.per_resident",1));town=value(yaml.getDouble("happiness.per_town",1));captured=value(yaml.getDouble("happiness.per_captured_town",2));
        distanceBase=value(yaml.getDouble("happiness.distance_base",.01));distanceTouching=value(yaml.getDouble("happiness.distance_multiplier",.75));distanceSeparate=value(yaml.getDouble("happiness.distance_multiplier_outside_culture",1.05));distanceMaximum=value(yaml.getDouble("happiness.distance_max",15));
        for(var raw:yaml.getMapList("happiness.town_levels"))bases.put(((Number)raw.get("level")).intValue(),value(((Number)raw.get("happiness")).doubleValue()));
        var list=new ArrayList<State>();
        for(var raw:yaml.getMapList("happiness.states")) {
            int level=((Number)raw.get("level")).intValue();
            String name=switch(level){case 0->"Бунт";case 1->"Недовольство";case 2->"Несчастье";case 3->"Довольство";case 4->"Счастье";case 5->"Ликование";case 6->"Восторг";default->String.valueOf(raw.get("name"));};
            list.add(new State(level,name,number(raw,"amount"),number(raw,"hammer_rate"),number(raw,"beaker_rate"),number(raw,"coin_rate"),number(raw,"culture_rate")));
        }
        list.sort(Comparator.comparingInt(State::level));states=List.copyOf(list);
        if(states.isEmpty() || states.getFirst().level()!=0)throw new IllegalArgumentException("Missing happiness state zero");
    }
    private static double number(Map<?,?> raw,String key){return value(((Number)raw.get(key)).doubleValue());}
    private static double value(double value){if(!Double.isFinite(value) || value<0)throw new IllegalArgumentException("Invalid happiness configuration");return value;}
    public double base(int level){var base=bases.get(level);if(base==null)throw new IllegalArgumentException("Unknown happiness town level");return base;}
    public double population(int residents,int foreignGroupMembers,int freeGroupMembers){return resident*(residents+.25*foreignGroupMembers+freeGroupMembers);}
    public double empire(int ordinaryTowns,int capturedTowns){return town*ordinaryTowns+captured*capturedTowns;}
    public double distance(double blocks,boolean connected){return Math.round(Math.min(distanceMaximum,distanceBase*Math.pow(blocks,connected?distanceTouching:distanceSeparate)));}
    public Result evaluate(double happy,double unhappy) {
        if(!Double.isFinite(happy) || !Double.isFinite(unhappy))throw new IllegalArgumentException("Invalid happiness source");
        happy=Math.max(0,happy);unhappy=Math.max(0,unhappy);
        double fraction=happy+unhappy==0?0:happy/(happy+unhappy),rounded=Math.round(fraction*100)/100.0;
        State selected=states.getFirst();for(var state:states)if(rounded>=state.threshold())selected=state;
        return new Result(happy,unhappy,fraction,selected);
    }
}
