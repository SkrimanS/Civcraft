package com.avrgaming.civcraft.modern.build;
import java.util.*;
public record ChestMarker(int id) {
    public static Optional<ChestMarker> parse(List<String> lines) {
        if(lines.size()<2 || !lines.get(0).trim().equalsIgnoreCase("/chest"))return Optional.empty();
        String identifier=lines.get(1).trim();if(!identifier.toLowerCase(Locale.ROOT).startsWith("id:"))return Optional.empty();
        try{return Optional.of(new ChestMarker(Integer.parseInt(identifier.substring(3).trim())));}catch(NumberFormatException invalid){return Optional.empty();}
    }
    public static String facing(byte data){return switch(Byte.toUnsignedInt(data)){case 4->"west";case 8->"north";case 12->"east";default->"south";};}
}
