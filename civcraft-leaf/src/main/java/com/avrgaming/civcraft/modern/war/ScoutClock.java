package com.avrgaming.civcraft.modern.war;
import java.util.*;
public final class ScoutClock {
    private final int interval;private final Map<Long,Integer> counts=new HashMap<>();
    public ScoutClock(int interval){this.interval=Math.max(1,interval);}
    public boolean ready(long build){int count=counts.getOrDefault(build,0)+1;if(count<interval){counts.put(build,count);return false;}counts.put(build,0);return true;}
}
