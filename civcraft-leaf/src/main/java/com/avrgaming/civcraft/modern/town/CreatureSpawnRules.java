package com.avrgaming.civcraft.modern.town;

import java.util.Set;

/** Active global rules from the original BlockListener; modern horse variants share the old HORSE type. */
public final class CreatureSpawnRules {
    private CreatureSpawnRules() {}
    private static final Set<String> HORSES=Set.of("HORSE","DONKEY","MULE","SKELETON_HORSE","ZOMBIE_HORSE");
    private static final Set<String> RESTRICTED=Set.of("BLAZE","CAVE_SPIDER","CREEPER","ENDER_DRAGON","ENDERMAN","GHAST","GIANT","ZOMBIFIED_PIGLIN","SILVERFISH","SKELETON","SLIME","SPIDER","WITCH","WITHER","ZOMBIE","BAT","ENDERMITE","GUARDIAN","HUSK","STRAY","ZOMBIE_VILLAGER");
    public static boolean horse(String type){return HORSES.contains(type);}
    public static boolean restricted(String type){return RESTRICTED.contains(type);}
    public static boolean denied(String type,String reason,boolean war){
        if(war && !horse(type) && !"BREEDING".equals(reason))return true;
        return "SPAWNER".equals(reason)
                || "CHICKEN".equals(type) && "EGG".equals(reason)
                || "IRON_GOLEM".equals(type) && "BUILD_IRONGOLEM".equals(reason);
    }
}
