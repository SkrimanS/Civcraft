package com.avrgaming.civcraft.modern.build;

import java.util.*;

/** AttributeTradeGood adds its value once per matching good in this town's hall. */
public record TradeGoodAttributes(List<Rule> rules) {
    public record Rule(String attribute,double value,Set<String> goods) {
        public Rule {goods=Set.copyOf(goods);}
    }
    public static final TradeGoodAttributes NONE=new TradeGoodAttributes(List.of());
    public TradeGoodAttributes {rules=List.copyOf(rules);}
    public static TradeGoodAttributes parse(Object components) {
        var rules=new ArrayList<Rule>();
        if(components instanceof List<?> list)for(var item:list)if(item instanceof Map<?,?> raw&&"AttributeTradeGood".equals(raw.get("name"))) {
            String attribute=Objects.toString(raw.get("attribute"),"").toUpperCase(Locale.ROOT);
            double value=Double.parseDouble(Objects.toString(raw.get("value"),"NaN"));
            var goods=new HashSet<String>();for(String id:Objects.toString(raw.get("goods"),"").split(","))if(!id.isBlank())goods.add(id.trim().toLowerCase(Locale.ROOT));
            if(attribute.isBlank()||!Double.isFinite(value)||goods.isEmpty())throw new IllegalArgumentException("Invalid trade good attribute");
            rules.add(new Rule(attribute,value,goods));
        }
        return new TradeGoodAttributes(rules);
    }
    public StaticAttributes calculate(Map<String,Integer> heldGoods) {
        var values=new LinkedHashMap<String,Double>();
        for(var rule:rules)for(var good:heldGoods.entrySet()) {
            if(good.getValue()<0)throw new IllegalArgumentException("Negative held trade good count");
            if(rule.goods().contains(good.getKey().toLowerCase(Locale.ROOT)))values.merge(rule.attribute(),rule.value()*good.getValue(),Double::sum);
        }
        if(values.values().stream().anyMatch(value->!Double.isFinite(value)))throw new IllegalArgumentException("Trade good attribute overflow");
        return new StaticAttributes(Map.copyOf(values));
    }
}
