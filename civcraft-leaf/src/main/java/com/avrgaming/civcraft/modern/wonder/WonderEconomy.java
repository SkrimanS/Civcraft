package com.avrgaming.civcraft.modern.wonder;
import java.sql.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
public record WonderEconomy(double upkeepReduction,double cultureCoins,double worldTownCoins,double leechRate,double peacefulTownCoins) {
    public WonderEconomy {if(!Double.isFinite(upkeepReduction)||upkeepReduction<0||upkeepReduction>1||!Double.isFinite(cultureCoins)||cultureCoins<0||!Double.isFinite(worldTownCoins)||worldTownCoins<0||!Double.isFinite(leechRate)||leechRate<0||leechRate>1||!Double.isFinite(peacefulTownCoins)||peacefulTownCoins<0)throw new IllegalArgumentException("Неверные экономические коэффициенты чуда");}
    public static WonderEconomy load(java.io.File file) {
        var values=new HashMap<String,Double>();for(var row:YamlConfiguration.loadConfiguration(file).getMapList("buffs"))if(row.get("id")!=null&&row.get("value")!=null){String id=row.get("id").toString();if(Set.of("buff_colossus_reduce_upkeep","buff_colossus_coins_from_culture","buff_colosseum_coins_from_towns","debuff_colossus_leech_upkeep","buff_notre_dame_coins_from_peace").contains(id))values.put(id,Double.parseDouble(row.get("value").toString()));}
        for(var id:List.of("buff_colossus_reduce_upkeep","buff_colossus_coins_from_culture","buff_colosseum_coins_from_towns","debuff_colossus_leech_upkeep","buff_notre_dame_coins_from_peace"))if(!values.containsKey(id))throw new IllegalArgumentException("Не настроен коэффициент чуда: "+id);
        return new WonderEconomy(values.get("buff_colossus_reduce_upkeep"),values.get("buff_colossus_coins_from_culture"),values.get("buff_colosseum_coins_from_towns"),values.get("debuff_colossus_leech_upkeep"),values.get("buff_notre_dame_coins_from_peace"));
    }
    public double upkeep(Connection db,long town,double base)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM active_structure_builds WHERE town_id=? AND structure_id='w_colossus' AND status='COMPLETE' LIMIT 1")){s.setLong(1,town);try(var r=s.executeQuery()){return r.next()?base*(1-upkeepReduction):base;}}
    }
    public static long coins(double rate,long units)throws SQLException {double amount=rate*units;if(!Double.isFinite(amount)||amount<0||amount>=0x1p63)throw new SQLException("Wonder income overflow");return (long)Math.floor(amount);}
}
