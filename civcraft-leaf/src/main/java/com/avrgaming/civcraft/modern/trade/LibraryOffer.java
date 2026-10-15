package com.avrgaming.civcraft.modern.trade;

import java.util.Locale;

/** Ordinary library research from town.yml; Great Library has a separate catalogue. */
public record LibraryOffer(String enchantment,int level,long price) {
    public LibraryOffer {
        enchantment=enchantment.toLowerCase(Locale.ROOT);
        int max=switch(enchantment){case "efficiency"->5;case "looting","fortune"->3;case "feather_falling"->4;case "silk_touch","loreenhancementsoulbound"->1;default->throw new IllegalArgumentException("Неизвестное зачарование библиотеки: "+enchantment);};
        if(level<1 || level>max || price<0 || price>1_000_000_000L)throw new IllegalArgumentException("Некорректное зачарование библиотеки.");
    }
    public static LibraryOffer parse(String value) {
        String[] parts=value.split(",");
        if(parts.length!=3)throw new IllegalArgumentException("Некорректное исследование библиотеки.");
        try{return new LibraryOffer(parts[0].trim(),Integer.parseInt(parts[1].trim()),new java.math.BigDecimal(parts[2].trim()).longValueExact());}
        catch(ArithmeticException invalid){throw new IllegalArgumentException("Цена зачарования должна быть целым числом монет.",invalid);}
    }
    public String name(){return switch(enchantment){case "looting"->"Добыча";case "efficiency"->"Эффективность";case "fortune"->"Удача";case "silk_touch"->"Шёлковое касание";case "feather_falling"->"Невесомость";default->"Невыпадение";};}
    public boolean soulbound(){return enchantment.equals("loreenhancementsoulbound");}
}
