package com.avrgaming.civcraft.modern.trade;

import java.util.*;

public record TradeShipMarker(Kind kind,int id) {
    public enum Kind { INCOMING, OUTGOING, IN_SIGN, OUT_SIGN, IN }
    public static Optional<TradeShipMarker> parse(List<String> lines) {
        if(lines.size()<2)return Optional.empty();
        Kind kind=switch(lines.getFirst().trim().toLowerCase(Locale.ROOT)) {
            case "/incoming"->Kind.INCOMING;case "/outgoing"->Kind.OUTGOING;
            case "/insign"->Kind.IN_SIGN;case "/outsign"->Kind.OUT_SIGN;case "/in"->Kind.IN;
            default->null;
        };
        if(kind==null)return Optional.empty();
        for(String line:lines.subList(1,lines.size())) {
            if(!line.trim().toLowerCase(Locale.ROOT).startsWith("id:"))continue;
            try {
                int id=Integer.parseInt(line.trim().substring(3).trim());
                if(id<0 || id>1000)return Optional.empty();
                return Optional.of(new TradeShipMarker(kind,id));
            }catch(NumberFormatException invalid){return Optional.empty();}
        }
        return Optional.empty();
    }
    public boolean chest() {return kind==Kind.INCOMING || kind==Kind.OUTGOING;}
    public boolean open(int upgrade,int level) {
        return switch(kind) {
            case INCOMING,IN_SIGN->TradeShipProgress.inputChest(id,upgrade);
            case OUTGOING,OUT_SIGN->TradeShipProgress.outputChest(id,level);
            case IN->true;
        };
    }
    public List<String> text(int upgrade,int level) {
        if(kind==Kind.IN)return List.of("Входящий груз",id==0?"1":"3",id==0?"2":"4","");
        boolean input=kind==Kind.IN_SIGN;
        if(open(upgrade,level))return List.of(input?"Входящий груз":"Исходящий груз",Integer.toString(id+1),"","");
        return List.of(input?"Входящий груз":"Исходящий груз","Недоступно",input?"Улучшение "+(id+1):"Уровень "+(id*2+1),"торгового судна");
    }
}
