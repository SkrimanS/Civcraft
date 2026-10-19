package com.avrgaming.civcraft.modern.town;

import java.util.*;

/** Pure inventory plan: live containers must only be changed after journaling this result. */
public final class ConsumptionPlan {
    private ConsumptionPlan() {}
    public record Exchange(int base, int alternative, int basePerAlternative) {
        public Exchange {
            if(base<1 || alternative<1 || base==alternative || basePerAlternative<1)
                throw new IllegalArgumentException("Неверный обмен еды");
        }
    }
    public record Meal(boolean enough,Map<Integer,Long> remaining) {
        public Meal {remaining=Map.copyOf(remaining);}
    }
    public record Cycle(ConsumptionRules.State state,Map<Integer,Long> remaining,double rawOutput) {
        public Cycle {remaining=Map.copyOf(remaining);}
    }

    public static Meal meal(Map<Integer,Integer> requirements,Map<Integer,Long> contents,
                            double rate,Collection<Exchange> exchanges) {
        validateContents(contents);
        var alternatives=new HashMap<Integer,Exchange>();
        var usedAlternatives=new HashSet<Integer>();
        for(var exchange:exchanges) {
            // The original cottage exchange is bread -> fish. Overlapping exchanges
            // would double-count shared food; reject ambiguous configuration.
            if(!requirements.containsKey(exchange.base()) || requirements.containsKey(exchange.alternative())
                    || alternatives.putIfAbsent(exchange.base(),exchange)!=null
                    || !usedAlternatives.add(exchange.alternative()))
                throw new IllegalArgumentException("Обмен еды пересекается с другим или не используется");
        }
        var remaining=new HashMap<>(contents);
        for(var required:requirements.entrySet()) {
            if(required.getKey()<1 || required.getValue()<1)throw new IllegalArgumentException("Неверное требование к еде");
            long amount=ConsumptionRules.amount(required.getValue(),rate);
            long base=remaining.getOrDefault(required.getKey(),0L);
            if(base>=amount) {
                put(remaining,required.getKey(),base-amount);
                continue;
            }
            var exchange=alternatives.get(required.getKey());
            if(exchange==null)return new Meal(false,contents);
            long shortfall=amount-base;
            long needed=(shortfall+exchange.basePerAlternative()-1)/exchange.basePerAlternative();
            long available=remaining.getOrDefault(exchange.alternative(),0L);
            if(available<needed)return new Meal(false,contents);
            put(remaining,exchange.alternative(),available-needed);
            // Exchange whole fish, returning the unused equivalent as bread, as
            // ConsumeLevelComponent did. A later adapter must place this change.
            put(remaining,exchange.base(),needed*exchange.basePerAlternative()-shortfall);
        }
        return new Meal(true,remaining);
    }

    public static Cycle cycle(Map<Integer,ConsumptionCatalog.Level> levels,int level,int count,
                              Map<Integer,Long> contents,double rate,Collection<Exchange> exchanges) {
        validateContents(contents);
        var current=levels.get(level);
        var next=levels.get(level+1);
        var previous=levels.get(level-1);
        var first=current==null?new Meal(false,contents):meal(current.food(),contents,rate,exchanges);
        // A second serving is checked at the CURRENT level; it is not consumed.
        boolean second=first.enough() && next!=null
                && meal(current.food(),first.remaining(),rate,exchanges).enough();
        var state=ConsumptionRules.advance(level,count,current==null?null:current.count(),
                next==null?null:next.count(),previous==null?null:previous.count(),first.enough(),second);
        return new Cycle(state,first.remaining(),state.consumed()?current.output():0);
    }
    private static void validateContents(Map<Integer,Long> contents) {
        if(contents.entrySet().stream().anyMatch(e->e.getKey()<1 || e.getValue()<0))
            throw new IllegalArgumentException("Неверное содержимое запаса еды");
    }
    private static void put(Map<Integer,Long> contents,int id,long amount) {
        if(amount==0)contents.remove(id);else contents.put(id,amount);
    }
}
