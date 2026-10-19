package com.avrgaming.civcraft.modern.town;

/** Count/level transitions from the original ConsumeLevelComponent. */
public final class ConsumptionRules {
    private ConsumptionRules() {}
    public enum Result {GROW,STARVE,LEVELUP,LEVELDOWN,MAXED,STAGNATE,UNKNOWN}
    public record State(int level,int count,Result result,boolean consumed) {}
    public static State advance(int level,int count,Integer maximum,Integer nextMaximum,Integer previousMaximum,boolean enough,boolean enoughAfterConsumption) {
        if(level<1 || count<0)throw new IllegalArgumentException("Неверное состояние потребления");
        if(maximum==null)return new State(level,count,Result.UNKNOWN,false);
        if(maximum<1 || (nextMaximum!=null && nextMaximum<1) || (previousMaximum!=null && previousMaximum<1))throw new IllegalArgumentException("Неверный предел уровня потребления");
        if(enough) {
            if((long)count+1>=maximum) {
                if(nextMaximum==null)return new State(level,count,Result.MAXED,true);
                if(enoughAfterConsumption)return new State(level+1,0,Result.LEVELUP,true);
                return new State(level,maximum,Result.MAXED,true);
            }
            return new State(level,count+1,Result.GROW,true);
        }
        if(count==0)return previousMaximum==null?new State(level,count,Result.STAGNATE,false):new State(level-1,previousMaximum,Result.LEVELDOWN,false);
        return new State(level,count-1,Result.STARVE,false);
    }
    public static int amount(int amount,double rate) {
        if(amount<0 || !Double.isFinite(rate) || rate<0 || amount*rate>Integer.MAX_VALUE)throw new IllegalArgumentException("Неверная скорость потребления еды");
        return (int)Math.max(1,amount*rate);
    }
}
