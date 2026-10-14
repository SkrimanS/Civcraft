package com.avrgaming.civcraft.modern.trade;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public record TradePoint(String action,String argument) {
    public static Optional<TradePoint> parse(List<String> lines) {
        if(lines.size()<2)return Optional.empty();
        String command=lines.get(0).trim().toLowerCase(Locale.ROOT);
        String argument=lines.get(1).trim().toLowerCase(Locale.ROOT);
        if(command.equals("/sign") && argument.startsWith("sell:"))return Optional.of(new TradePoint("bank",argument.substring(5)));
        if(command.equals("/sign") && argument.startsWith("slot:")) {
            try{int slot=Integer.parseInt(argument.substring(5));if(slot>=0 && slot<4)return Optional.of(new TradePoint("grocer",String.valueOf(slot)));}
            catch(NumberFormatException ignored){}
            return Optional.empty();
        }
        if(command.equals("/bigbig"))command="/buybig";
        if(List.of("/buy","/buybig","/sell","/sellbig").contains(command) && argument.startsWith("id:")) {
            try {return Optional.of(new TradePoint(command.substring(1),String.valueOf(Integer.parseInt(argument.substring(3)))));}
            catch(NumberFormatException ignored){return Optional.empty();}
        }
        return Optional.empty();
    }
}
