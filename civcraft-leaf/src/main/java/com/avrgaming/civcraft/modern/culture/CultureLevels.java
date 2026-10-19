package com.avrgaming.civcraft.modern.culture;
import java.util.*;
public final class CultureLevels {
    public record Level(int number,long amount,int radius) {}
    private final List<Level> levels;
    public CultureLevels(List<? extends Map<?,?>> entries) {
        var sorted=new TreeMap<Integer,Level>();
        for(var row:entries){int n=((Number)row.get("level")).intValue();long amount=((Number)row.get("amount")).longValue();int radius=((Number)row.get("chunks")).intValue();
            if(n<0 || amount<0 || radius<1 || radius>64 || sorted.put(n,new Level(n,amount,radius))!=null)throw new IllegalArgumentException("Неверный уровень культуры");}
        if(sorted.isEmpty() || sorted.firstKey()!=0)throw new IllegalArgumentException("Не настроен нулевой уровень культуры");
        levels=List.copyOf(sorted.values());long previous=-1;
        for(int i=0;i<levels.size();i++){var level=levels.get(i);if(level.number()!=i || level.amount()<previous)throw new IllegalArgumentException("Уровни культуры должны идти подряд");previous=level.amount();}
    }
    /** Original Town.getCultureLevel returns the first threshold above accumulated culture. */
    public Level forCulture(long culture) {
        int index=0;while(index+1<levels.size() && culture>=levels.get(index).amount())index++;
        return levels.get(index);
    }
}
