package com.avrgaming.civcraft.modern.war;

import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;

/** Deployable cannon settings; deliberately independent of cannon_tower. */
public record SiegeCannonCatalog(String template,int tnt,int cooldownSeconds,int health,double radius,double playerDamage,int structureDamage,double maximumRange,int controlDamage) {
    public SiegeCannonCatalog {
        if(template==null || !template.matches("[a-zA-Z0-9_-]+") || tnt<1 || cooldownSeconds<0 || health<1 || !Double.isFinite(radius) || radius<0 || radius>64 || !Double.isFinite(playerDamage) || playerDamage<0 || structureDamage<0 || !Double.isFinite(maximumRange) || maximumRange<=0 || controlDamage<0)
            throw new IllegalArgumentException("Invalid deployable cannon configuration");
    }
    public static SiegeCannonCatalog load(File war) {
        var y=YamlConfiguration.loadConfiguration(war);
        return new SiegeCannonCatalog(y.getString("cannon.template","cannon"),y.getInt("cannon.tnt_cost",3),y.getInt("cannon.cooldown",30),y.getInt("cannon.hitpoints",50),y.getDouble("cannon.yield",7),y.getDouble("cannon.player_damage",50),y.getInt("cannon.structure_damage",100),y.getDouble("cannon.max_range",256),y.getInt("cannon.control_block_hp",1));
    }
}
