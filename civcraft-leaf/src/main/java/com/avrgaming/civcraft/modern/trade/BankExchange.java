package com.avrgaming.civcraft.modern.trade;

/** The legacy bank multiplies its level rate by the sum of its town's barter buffs. */
public final class BankExchange {
    private BankExchange() {}
    public static double rate(int level,double barter) {
        if(level<1||level>10||!Double.isFinite(barter)||barter<0)throw new IllegalArgumentException("Некорректный курс банка.");
        double result=new double[]{.4,.5,.6,.7,.8,.9,1,1.2,1.5,2}[level-1]*(1+barter);
        if(!Double.isFinite(result))throw new IllegalArgumentException("Переполнение курса банка.");
        return result;
    }
    public static long gross(double base,int count,double rate) {
        double result=base*count*rate;
        if(!Double.isFinite(base)||base<0||count<1||!Double.isFinite(rate)||rate<0||!Double.isFinite(result)||result>=0x1p63)
            throw new IllegalArgumentException("Некорректная сумма обмена.");
        return (long)result;
    }
}
