package com.avrgaming.civcraft.modern.build;

import java.io.File;
import java.sql.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Buildable.getHammerCost: Rush and Mother Tree reductions add before multiplication. */
public final class HammerCosts {
    private final Set<String> improvements;
    private final double motherTree;
    public HammerCosts(File folder) {
        var ids=new HashSet<String>();
        for(var raw:YamlConfiguration.loadConfiguration(new File(folder,"structures.yml")).getMapList("structures"))if(Boolean.TRUE.equals(raw.get("tile_improvement")))ids.add(Objects.toString(raw.get("id")));
        improvements=Set.copyOf(ids);Double value=null;
        for(var buff:YamlConfiguration.loadConfiguration(new File(folder,"buffs.yml")).getMapList("buffs"))if("buff_mother_tree_tile_improvement_cost".equals(buff.get("id")))value=Double.parseDouble(Objects.toString(buff.get("value")));
        if(value==null||!Double.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Invalid Mother Tree hammer cost bonus");motherTree=value;
    }
    public double cost(Connection db,long town,String type,double base,double rush)throws SQLException {
        boolean mother=false;
        if(type!=null&&improvements.contains(type))try(var s=db.prepareStatement("""
            SELECT 1 FROM active_structure_builds b JOIN towns source ON source.id=b.town_id
            JOIN towns target ON target.civ_id=source.civ_id LEFT JOIN building_combat_health h ON h.build_id=b.id
            WHERE target.id=? AND b.structure_id='w_mother_tree' AND b.status='COMPLETE'
              AND (b.max_hitpoints=0 OR COALESCE(h.hitpoints,b.max_hitpoints)>0) LIMIT 1
            """)){s.setLong(1,town);try(var r=s.executeQuery()){mother=r.next();}}
        return apply(base,rush,mother?motherTree:0);
    }
    public static double apply(double base,double rush,double motherTree) {
        for(double value:new double[]{base,rush,motherTree})if(!Double.isFinite(value)||value<0)throw new IllegalArgumentException("Invalid building hammer cost");
        double rate=1-rush-motherTree;
        if(rate<0)throw new IllegalArgumentException("Building hammer cost reduction exceeds 100%");
        double result=base*rate;if(!Double.isFinite(result))throw new IllegalArgumentException("Building hammer cost overflow");
        return result;
    }
}
