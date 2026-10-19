package com.avrgaming.civcraft.modern.trade;
import java.io.File;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
public final class GrocerCatalog {
    public record Entry(int level,int item,int data,int amount,long price,String name) {}
    private final Map<Integer,Entry> entries=new TreeMap<>();
    public GrocerCatalog(File structures) {
        for(var row:YamlConfiguration.loadConfiguration(structures).getMapList("grocer_levels")) {
            int level=integer(row.get("level")),item=integer(row.get("itemId")),data=integer(row.get("itemData")),amount=integer(row.get("amount"));
            double price=((Number)row.get("price")).doubleValue();
            if(level<1 || level>4 || item<1 || data<0 || data>15 || amount<1 || amount>64 || !Double.isFinite(price) || price<0 || price!=Math.rint(price) || price>1_000_000_000_000L)
                throw new IllegalArgumentException("Неверное предложение бакалеи");
            String name=switch(item){case 350->"Жареная треска";case 366->"Жареная курица";case 320->"Жареная свинина";case 396->"Золотая морковь";default->String.valueOf(row.get("itemName"));};
            if(entries.putIfAbsent(level,new Entry(level,item,data,amount,(long)price,name))!=null)throw new IllegalArgumentException("Повторный уровень бакалеи");
        }
        if(entries.size()!=4)throw new IllegalArgumentException("Требуются четыре уровня бакалеи");
    }
    public Entry require(int level){var e=entries.get(level);if(e==null)throw new IllegalArgumentException("Неизвестный товар бакалеи.");return e;}
    private static int integer(Object v){if(!(v instanceof Number n) || n.doubleValue()!=n.intValue())throw new IllegalArgumentException("Неверное целое число в настройках бакалеи");return n.intValue();}
    public static long fee(long price,double rate,boolean member) {
        if(price<0 || !Double.isFinite(rate) || rate<0 || rate>1)throw new IllegalArgumentException("Некорректная комиссия бакалеи.");
        return member?0:Math.round(price*rate);
    }
}
