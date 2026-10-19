package com.avrgaming.civcraft.modern.build;

import java.util.*;

/** Exact ordinary signs from the shipped templates; command markers remain machine-readable. */
public final class LegacySignText {
    private LegacySignText() {}
    private static final Map<List<String>,List<String>> TRANSLATIONS=create();
    private static Map<List<String>,List<String>> create() {
        var result=new HashMap<List<String>,List<String>>();
        add(result,List.of("","Camp","Maintenance","Info:"),List.of("","Содержание","лагеря",""));
        add(result,List.of("","\\Camp Raid Info","",""),List.of("","Рейды лагеря","",""));
        add(result,List.of("Grave Yard","","Wait for","Respawn"),List.of("Кладбище","","Ожидайте","возрождения"));
        add(result,List.of("If the fire ","\\dies your camp","will be ","destroyed."),List.of("Если огонь","угаснет, лагерь","будет","разрушен."));
        add(result,List.of("Place bread ","\\here to increa","\\coin generatio","of the cabin"),List.of("Положите хлеб","сюда, чтобы","повысить доход","большого дома."));
        add(result,List.of("Store your ","valuables in","\\your enderches","DO NOT RELY"),List.of("Ценные вещи","храните только","в личном","сундуке Края."));
        add(result,List.of("The camp raid","period is ","\\is set to what","ever time"),List.of("Рейд начинается","в то же время,","когда лагерь","был построен."));
        add(result,List.of("The camp was ","\\constructed at","and lasts 2","\\hours every da"),List.of("Окно рейда:","2 часа в день.","Вне этого окна","лагерь защищён."));
        for(int first:new int[]{1,3,5,7})add(result,List.of("Trade Good","Slot:"+first,"Slot:"+(first+1),""),List.of("Торговый товар","Ячейка: "+first,"Ячейка: "+(first+1),""));
        add(result,List.of("WARNING:","Camps can be ","destroyed","if civs build"),List.of("ВНИМАНИЕ!","Чужой город","может разрушить","ваш лагерь."));
        add(result,List.of("\\If the campfir","runs out of","\\coal the fire ","\\will slowly di"),List.of("Без угля","в печах лагеря","костёр начнёт","угасать."));
        add(result,List.of("\\If your camp i","\\raided all ite","\\in conventiona","chests"),List.of("При набеге","на ваш лагерь","вещи из обычных","сундуков могут"));
        add(result,List.of("\\Put coal in th","furnaces to ","keep the fire","\\and camp alive"),List.of("Кладите уголь","в печи лагеря.","Он поддержит","огонь и лагерь."));
        add(result,List.of("\\The camp is no","raidable for ","the first 24","hours."),List.of("Первые 24 часа","после постройки","лагерь защищён","от набегов."));
        add(result,List.of("\\can be stolen ","\\so be sure to ","to use the","ender chest."),List.of("быть похищены.","Храните ценное","в личном","сундуке Края."));
        add(result,List.of("\\near civs if i","\\can be avoided","and be sure","to keep your "),List.of("Ценные вещи","всегда храните","в сундуке Края,","а не в лагере."));
        add(result,List.of("\\near or ontop ","of your camp","","\\to prevent thi"),List.of("Не стройте","лагерь рядом","с чужими","городами."));
        add(result,List.of("\\your valuables","in your ender","chest at all ","times."),List.of("Держите ценное","в сундуке Края","на случай","потери лагеря."));
        add(result,List.of("on any other ","chests within","the camp for","safe storage"),List.of("Обычный сундук","не защитит вещи","во время рейда","на ваш лагерь."));
        return Map.copyOf(result);
    }
    private static void add(Map<List<String>,List<String>> target,List<String> before,List<String> after) {
        if(before.size()!=4 || after.size()!=4 || after.stream().anyMatch(line->line.length()>15))throw new IllegalStateException("Некорректный перевод таблички шаблона.");
        if(target.putIfAbsent(key(before),List.copyOf(after))!=null)throw new IllegalStateException("Повторный перевод таблички шаблона.");
    }
    private static List<String> key(List<String> lines) {
        var result=new ArrayList<String>(4);
        for(int i=0;i<4;i++){String text=i<lines.size()?Objects.requireNonNullElse(lines.get(i),""):"";text=text.strip();while(text.startsWith("\\"))text=text.substring(1);result.add(text.strip().toLowerCase(Locale.ROOT));}
        return List.copyOf(result);
    }
    public static Optional<List<String>> translation(List<String> original) {
        if(original.isEmpty() || original.getFirst().stripLeading().startsWith("/"))return Optional.empty();
        return Optional.ofNullable(TRANSLATIONS.get(key(original)));
    }
    public static List<String> display(List<String> original){return translation(original).orElse(original);}
}
