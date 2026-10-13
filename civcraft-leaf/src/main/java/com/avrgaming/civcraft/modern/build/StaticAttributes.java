package com.avrgaming.civcraft.modern.build;

import java.util.*;

/** Declarative AttributeStatic components from the original structure configuration. */
public record StaticAttributes(Map<String,Double> values) {
    public static StaticAttributes parse(Object components) {
        Map<String,Double> values=new LinkedHashMap<>();
        if(components instanceof List<?> list)for(Object item:list)if(item instanceof Map<?,?> raw && "AttributeStatic".equals(raw.get("name"))) {
            String attribute=String.valueOf(raw.get("attribute")).toUpperCase(Locale.ROOT);
            double value=Double.parseDouble(String.valueOf(raw.get("value")));
            if(!Double.isFinite(value))throw new IllegalArgumentException("Invalid static attribute: "+attribute);
            values.merge(attribute,value,Double::sum);
        }
        return new StaticAttributes(Map.copyOf(values));
    }
    public double get(String name){return values.getOrDefault(name.toUpperCase(Locale.ROOT),0.0);}
    public double education(String structureId,int residents) {
        int maximum=switch(structureId){case "s_university"->5;case "s_school","s_research_lab"->10;default->0;};
        return get("BEAKERBOOST")*Math.min(Math.max(0,residents),maximum);
    }
    public int happiness(){return (int)Math.round(get("HAPPINESS")-get("UNHAPPINESS"));}
}
