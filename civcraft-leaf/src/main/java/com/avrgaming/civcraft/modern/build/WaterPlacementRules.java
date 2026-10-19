package com.avrgaming.civcraft.modern.build;
import java.util.Set;
public final class WaterPlacementRules {
    private WaterPlacementRules() {}
    private static final Set<String> TYPES=Set.of("s_shipyard","s_arrowship","s_cannonship","s_scoutship","ti_trade_ship","ti_fishing_boat");
    public record Origin(int x,int y,int z) {}
    public static boolean water(String id){return id!=null&&TYPES.contains(id);}
    public static void requireHeight(String id,int playerY){if(water(id)&&playerY>82)throw new IllegalArgumentException("Корабли, лодки и верфь строятся у уровня моря. Спуститесь до Y=82 или ниже.");}
    public static Origin origin(String id,int shift,double x,double y,double z,int sizeX,int sizeZ,String direction) {
        if(!water(id)||sizeX<1||sizeZ<1)throw new IllegalArgumentException("Неверное положение водной постройки");
        double ox=x,oz=z;
        if(id.startsWith("ti_")){ox=Math.floor(x/16)*16;oz=Math.floor(z/16)*16;}
        else switch(direction){case "90","east"->oz-=sizeZ/2.0;case "270","west"->{ox-=sizeX;oz-=sizeZ/2.0;}case "0","north"->{ox-=sizeX/2.0;oz-=sizeZ;}case "180","south"->ox-=sizeX/2.0;default->throw new IllegalArgumentException("Неверное направление водной постройки");}
        return new Origin((int)Math.floor(ox),shift==0?(int)Math.floor(y):62+shift,(int)Math.floor(oz));
    }
}
