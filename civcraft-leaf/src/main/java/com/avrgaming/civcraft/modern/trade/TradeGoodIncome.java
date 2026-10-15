package com.avrgaming.civcraft.modern.trade;

import java.io.File;
import java.math.BigDecimal;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

public final class TradeGoodIncome {
    private final TradeGoodsCatalog goods;
    private final int duplicateCap;
    private final Map<String,Double> structureRates;
    public TradeGoodIncome(File folder) {
        goods=new TradeGoodsCatalog(new File(folder,"goods.yml"));
        duplicateCap=YamlConfiguration.loadConfiguration(new File(folder,"goods.yml")).getInt("trade_good_multiplier_max",3);
        if(duplicateCap<0)throw new IllegalArgumentException("Invalid duplicate trade good cap");
        var rates=new HashMap<String,Double>();
        for(var raw:YamlConfiguration.loadConfiguration(new File(folder,"structures.yml")).getMapList("structures")) {
            if(raw.get("components") instanceof List<?> components)for(var item:components)if(item instanceof Map<?,?> component
                    && "AttributeRate".equals(component.get("name"))&&"TRADE".equalsIgnoreCase(Objects.toString(component.get("attribute"),""))) {
                double value=Double.parseDouble(Objects.toString(component.get("value"),"NaN"));
                if(!Double.isFinite(value)||value<0)throw new IllegalArgumentException("Invalid structure trade rate");
                rates.merge(Objects.toString(raw.get("id")),value,Double::sum);
            }
        }
        structureRates=Map.copyOf(rates);
    }
    public double structureRate(String id){return structureRates.getOrDefault(id,0.0);}
    public String name(String id){return goods.require(id).name();}
    /** Inactive sources still count towards the same-type multiplier, as in legacy getTradeGoodCount. */
    public BigDecimal base(Collection<String> payable,Map<String,Integer> held) {
        var names=new HashMap<String,Integer>();
        held.forEach((id,count)->{if(count<0)throw new IllegalArgumentException("Negative trade good count");names.merge(goods.require(id).name(),count,Math::addExact);});
        BigDecimal total=BigDecimal.ZERO;
        for(String id:payable) {
            var good=goods.require(id);int count=names.getOrDefault(good.name(),0);
            if(count<1)throw new IllegalArgumentException("Payable good is not held by town");
            BigDecimal rate=BigDecimal.ONE.add(BigDecimal.valueOf(Math.min(duplicateCap,count-1)).multiply(new BigDecimal("0.5")));
            total=total.add(BigDecimal.valueOf(good.value()).multiply(rate));
        }
        return total;
    }
    public static TradeShipIncome.Payment payment(BigDecimal base,double government,double structures,double monopoly,double happiness,double tax) {
        if(base.signum()<0)throw new IllegalArgumentException("Negative trade good base");
        for(double value:new double[]{government,structures,monopoly,happiness,tax})if(!Double.isFinite(value)||value<0)throw new IllegalArgumentException("Invalid trade good income rate");
        if(tax>1)throw new IllegalArgumentException("Invalid trade good income tax");
        BigDecimal gross=base.multiply(BigDecimal.valueOf(government).add(BigDecimal.valueOf(structures)))
            .multiply(BigDecimal.ONE.add(BigDecimal.valueOf(monopoly))).multiply(BigDecimal.valueOf(happiness));
        BigDecimal tribute=gross.multiply(BigDecimal.valueOf(tax));
        return new TradeShipIncome.Payment(gross.subtract(tribute),tribute,0);
    }
}
