package com.avrgaming.civcraft.modern.town;

import java.util.*;

/** The old limit counts registered offspring of all species together, not the wild parents. */
public final class PastureRules {
    private PastureRules() {}
    public static boolean controlled(String type){return Set.of("COW","SHEEP","MOOSHROOM","PIG","CHICKEN","RABBIT").contains(type);}
    public static boolean horse(String type){return "HORSE".equals(type);}
    public static boolean room(long animals,long pending,int maximum){
        if(animals<0 || pending<0 || maximum<0)throw new IllegalArgumentException("Invalid pasture population");
        return animals<maximum && pending<maximum-animals;
    }
}
