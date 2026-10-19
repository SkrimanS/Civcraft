package com.avrgaming.civcraft.modern.trade;

import java.io.File;
import java.sql.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Rebuild effects from canonical ownership, so removal and restart cannot leave cached buffs. */
public final class GoodieEffects {
    private record Buff(String id,String parent,double value,boolean stackable) {}
    public record Values(Map<String,Double> buffs,Map<String,Integer> goods) {
        public static final Values NONE=new Values(Map.of(),Map.of());
        public Values {buffs=Map.copyOf(buffs);goods=Map.copyOf(goods);}
        public double value(String parent){return buffs.getOrDefault(parent,0.0);}
        public int count(String good){return goods.getOrDefault(good,0);}
        public double happiness(){return goods.values().stream().mapToInt(Integer::intValue).sum()+value("buff_hedonism");}
        public double culture(){return value("buff_fine_art")+value("buff_art_appreciation");}
        public double cottageFood(double wonderFactor){return wonderFactor*(buffs.containsKey("buff_preservative")?value("buff_preservative"):1);}
        public List<com.avrgaming.civcraft.modern.town.ConsumptionPlan.Exchange> cottageExchanges() {
            double fish=value("buff_fishing");
            if(fish==0)return List.of();
            if(fish<1||fish!=Math.rint(fish)||fish>Integer.MAX_VALUE)throw new IllegalArgumentException("Неверное соотношение еды для рыбалки");
            return List.of(new com.avrgaming.civcraft.modern.town.ConsumptionPlan.Exchange(297,349,(int)fish));
        }
    }
    private final TradeGoodsCatalog goods;
    private final Map<String,Buff> buffs;
    public GoodieEffects(TradeGoodsCatalog goods,File file) {
        this.goods=goods;var loaded=new LinkedHashMap<String,Buff>();
        for(var raw:YamlConfiguration.loadConfiguration(file).getMapList("buffs")) {
            String id=Objects.toString(raw.get("id"),"");
            String parent=Objects.toString(raw.get("parent"),id);if(parent.isBlank())parent=id;
            double value=Double.parseDouble(Objects.toString(raw.get("value"),"NaN"));
            if(id.isBlank()||!Double.isFinite(value)||value<0||!(raw.get("stackable") instanceof Boolean))throw new IllegalArgumentException("Неверный бонус торгового товара: "+id);
            if(loaded.putIfAbsent(id,new Buff(id,parent,value,(Boolean)raw.get("stackable")))!=null)throw new IllegalArgumentException("Повторный бонус торгового товара: "+id);
        }
        for(var good:goods.list())for(String id:good.buffs())if(!loaded.containsKey(id))throw new IllegalArgumentException("Не настроен бонус торгового товара: "+id);
        buffs=Map.copyOf(loaded);
    }
    public Values calculate(Collection<String> heldGoods) {
        var totals=new HashMap<String,Double>();var counts=new HashMap<String,Integer>();var used=new HashSet<String>();
        for(String id:heldGoods) {
            var good=goods.require(id);counts.merge(id,1,Integer::sum);
            // One source may only apply a given buff ID once, as in the legacy buff-key registry.
            for(String buffId:new LinkedHashSet<>(good.buffs())) {
                var buff=buffs.get(buffId);
                if(buff.stackable()||used.add(buff.id()))totals.merge(buff.parent(),buff.value(),Double::sum);
            }
        }
        return new Values(totals,counts);
    }
    public Map<String,Double> goodsForBuff(String parent) {
        var result=new LinkedHashMap<String,Double>();
        for(var good:goods.list()){double value=calculate(List.of(good.id())).value(parent);if(value>0)result.put(good.id(),value);}
        return Map.copyOf(result);
    }
    public Values town(Connection db,long town)throws SQLException {
        var held=new ArrayList<String>();
        try(var s=db.prepareStatement("""
            SELECT g.good_id FROM bonus_goodies g
            JOIN goodie_frames f ON f.frame_key=g.holder_id AND f.is_source=0
            JOIN active_structure_builds hall ON hall.id=f.build_id AND hall.town_id=g.host_town
            JOIN active_structure_builds origin ON origin.id=g.origin_build
            WHERE g.holder_kind='FRAME' AND g.host_town=? AND hall.status='COMPLETE'
              AND hall.structure_id IN ('s_capitol','s_townhall') AND origin.status='COMPLETE'
              AND NOT EXISTS(SELECT 1 FROM goodie_transfers transfer WHERE transfer.origin_build=g.origin_build AND transfer.status='PREPARED')
            ORDER BY g.origin_build
            """)) {
            s.setLong(1,town);try(var r=s.executeQuery()){while(r.next())held.add(r.getString(1));}
        }
        return calculate(held);
    }
    /** TownHall checks all towns of its civilization; the nonstacking medicine buff applies once. */
    public int medicine(Connection db,long civilization)throws SQLException {
        var towns=new ArrayList<Long>();
        try(var s=db.prepareStatement("SELECT id FROM towns WHERE civ_id=? ORDER BY id")){s.setLong(1,civilization);try(var r=s.executeQuery()){while(r.next())towns.add(r.getLong(1));}}
        int result=0;
        for(long town:towns) {
            double value=town(db,town).value("buff_medicine");
            if(value<0||value!=Math.rint(value)||value>Integer.MAX_VALUE)throw new IllegalArgumentException("Неверный бонус медицины к возрождению");
            result=Math.max(result,(int)value);
        }
        return result;
    }
}
