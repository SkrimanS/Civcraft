package com.avrgaming.civcraft.modern.wonder;
import java.sql.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
public final class WonderBonuses {
    public record Values(double science,double growth,double culture,double cottageFood,double cottageIncome) {
        public static final Values NONE=new Values(0,0,0,1,1);
    }
    private final Map<String,Double> values;
    public WonderBonuses(Map<String,Double> values){this.values=Map.copyOf(values);if(values.values().stream().anyMatch(v->!Double.isFinite(v)||v<0))throw new IllegalArgumentException("Invalid wonder bonus");}
    public static WonderBonuses load(java.io.File file) {
        var values=new HashMap<String,Double>();for(var raw:YamlConfiguration.loadConfiguration(file).getMapList("buffs"))if(raw.get("id")!=null&&raw.get("value")!=null){String id=raw.get("id").toString();if(Set.of("buff_greatlibrary_extra_beakers","buff_hanging_gardens_growth","buff_mother_tree_growth","buff_pyramid_culture","buff_pyramid_cottage_consume","buff_pyramid_cottage_bonus").contains(id))values.put(id,Double.parseDouble(raw.get("value").toString()));}
        var result=new WonderBonuses(values);for(String id:Set.of("buff_greatlibrary_extra_beakers","buff_hanging_gardens_growth","buff_mother_tree_growth","buff_pyramid_culture","buff_pyramid_cottage_consume","buff_pyramid_cottage_bonus"))if(!values.containsKey(id))throw new IllegalArgumentException("Missing wonder buff: "+id);return result;
    }
    public Values town(Connection db,long town)throws SQLException {
        var civilization=new HashSet<String>();var local=new HashSet<String>();
        try(var s=db.prepareStatement("SELECT b.structure_id,b.town_id FROM structure_builds b JOIN towns source ON source.id=b.town_id JOIN towns target ON target.civ_id=source.civ_id WHERE target.id=? AND b.status='COMPLETE' AND b.structure_id IN ('w_greatlibrary','w_hanginggardens','w_mother_tree','w_pyramid')")){
            s.setLong(1,town);try(var r=s.executeQuery()){while(r.next()){civilization.add(r.getString(1));if(r.getLong(2)==town)local.add(r.getString(1));}}
        }
        return new Values(civilization.contains("w_greatlibrary")?value("buff_greatlibrary_extra_beakers"):0,
            (civilization.contains("w_hanginggardens")?value("buff_hanging_gardens_growth"):0)+(civilization.contains("w_mother_tree")?value("buff_mother_tree_growth"):0),
            civilization.contains("w_pyramid")?value("buff_pyramid_culture"):0,
            local.contains("w_pyramid")?value("buff_pyramid_cottage_consume"):1,local.contains("w_pyramid")?value("buff_pyramid_cottage_bonus"):1);
    }
    private double value(String id){var value=values.get(id);if(value==null)throw new IllegalArgumentException("Missing wonder buff: "+id);return value;}
    public static long cottageOutput(double raw,double pyramid,double taxation) {
        double first=Math.round(raw)*pyramid;if(!Double.isFinite(first)||first<0||first>Long.MAX_VALUE/2.0)throw new IllegalArgumentException("Invalid cottage wonder income");
        double second=(long)first*taxation;if(!Double.isFinite(second)||second<0||second>Long.MAX_VALUE/2.0)throw new IllegalArgumentException("Invalid cottage taxation income");return (long)second;
    }
}
