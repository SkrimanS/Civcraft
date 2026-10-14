package com.avrgaming.civcraft.modern.war;
import java.util.HashMap;
import java.util.Map;
/** The original counter advances only when a valid target reaches fire(). */
public final class TeslaFireClock {
    private final int delay;private final Map<Long,Integer> counts=new HashMap<>();
    public TeslaFireClock(int delay){if(delay<0)throw new IllegalArgumentException("Negative Tesla delay");this.delay=delay;}
    public boolean ready(long tower){int count=counts.getOrDefault(tower,0);if(count<delay){counts.put(tower,count+1);return false;}counts.put(tower,0);return true;}
}
