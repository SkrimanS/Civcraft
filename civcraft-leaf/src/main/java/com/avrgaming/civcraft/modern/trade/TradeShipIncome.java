package com.avrgaming.civcraft.modern.trade;

import java.math.BigDecimal;
import java.io.File;
import java.util.Map;
import java.util.HashMap;
import org.bukkit.configuration.file.YamlConfiguration;

public final class TradeShipIncome {
    private TradeShipIncome() {}
    public record Rates(double ingermanland,double greatLighthouse,double lighthouse) {
        public Rates {for(double rate:new double[]{ingermanland,greatLighthouse,lighthouse})
            if(!Double.isFinite(rate)||rate<0)throw new IllegalArgumentException("Неверный множитель дохода торгового корабля");}
    }
    public record Payment(BigDecimal town,BigDecimal civilization,long culture) {}
    public static Rates rates(File dataFolder) {
        Map<String,Double> buffs=new HashMap<>();
        for(var buff:YamlConfiguration.loadConfiguration(new File(dataFolder,"buffs.yml")).getMapList("buffs"))
            buffs.put(String.valueOf(buff.get("id")),Double.parseDouble(String.valueOf(buff.get("value"))));
        Double ship=buffs.get("buff_ingermanland_trade_ship_income"),great=buffs.get("buff_great_lighthouse_trade_ship_income");
        if(ship==null || great==null)throw new IllegalArgumentException("Не настроены бонусы дохода торгового корабля");
        var town=YamlConfiguration.loadConfiguration(new File(dataFolder,"town.yml"));
        if(!town.contains("town.lighthouse_trade_ship_boost"))throw new IllegalArgumentException("Не настроен множитель дохода маяка");
        return new Rates(ship,great,town.getDouble("town.lighthouse_trade_ship_boost"));
    }
    public static Payment calculate(double money,long culture,double tax,Rates rates,
                                    boolean ingermanland,boolean greatLighthouse,boolean lighthouse) {
        if(!Double.isFinite(money)||money<0||culture<0||!Double.isFinite(tax)||tax<0||tax>1)
            throw new IllegalArgumentException("Неверный доход торгового корабля");
        BigDecimal gross=BigDecimal.ZERO;
        // Original process_trade_ship only enters the money branch when raw income >= 1.
        if(money>=1) {
            gross=BigDecimal.valueOf(money);
            if(ingermanland)gross=gross.multiply(BigDecimal.valueOf(rates.ingermanland()));
            if(greatLighthouse)gross=gross.multiply(BigDecimal.valueOf(rates.greatLighthouse()));
            if(lighthouse)gross=gross.multiply(BigDecimal.valueOf(rates.lighthouse()));
        }
        BigDecimal tribute=gross.multiply(BigDecimal.valueOf(tax));
        return new Payment(gross.subtract(tribute),tribute,culture);
    }
}
