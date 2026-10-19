package com.avrgaming.civcraft.modern.camp;

/** Source Date.after(start) and inclusive end; stale days advance in one calculation. */
public final class CampRaidRules {
    private CampRaidRules() {}
    public static final long DAY=86400000L;
    public record Window(long start,long end,boolean open) {}
    public static Window window(long start,long duration,long now) {
        if(start<0 || duration<1 || duration>DAY || now<0)throw new IllegalArgumentException("Неверные параметры часов рейдов лагеря");
        long end=Math.addExact(start,duration);
        if(now>end) {
            long days=(now-end-1)/DAY+1;
            start=Math.addExact(start,Math.multiplyExact(days,DAY));end=Math.addExact(start,duration);
        }
        return new Window(start,end,now>start && now<=end);
    }
}
