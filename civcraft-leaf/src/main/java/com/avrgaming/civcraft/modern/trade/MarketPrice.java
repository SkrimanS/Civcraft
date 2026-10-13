package com.avrgaming.civcraft.modern.trade;

/** Original ConfigMarketItem pricing, including the twofold buy-price surcharge. */
public record MarketPrice(long sell,long buy,int counter) {
    public record Quote(long coins,MarketPrice next) {}
    public static MarketPrice initial(long base,double rate) {
        return new MarketPrice(base,buyValue(base,rate),0);
    }
    private static long buyValue(long sell,double rate) {return Math.max(sell+1,sell+(long)(sell*rate));}
    public Quote trade(boolean buying,int amount,int step,int stepCount,double rate,boolean stackable) {
        if(amount<1 || amount>64 || step<1 || stepCount<1)throw new IllegalArgumentException("Invalid market amount or price step");
        long priceSell=sell,priceBuy=buy,total=0;
        int count=counter;
        if(buying) {
            long quoted=buy;
            for(int index=0;index<amount;index++) {
                total=Math.addExact(total,Math.multiplyExact(quoted,2));
                if((index+1)%stepCount==0)quoted=Math.addExact(quoted,step);
            }
        }
        for(int index=0;index<amount;index++) {
            if(!buying)total=Math.addExact(total,priceSell);
            count+=buying?1:-1;
            if(count%stepCount==0 || !stackable) {
                priceSell=Math.addExact(priceSell,buying?step:-step);
                priceBuy=buyValue(priceSell,rate);
                if(priceSell<step){priceSell=step;priceBuy=step*2L;}
                count=0;
            }
        }
        return new Quote(total,new MarketPrice(priceSell,priceBuy,count));
    }
}
