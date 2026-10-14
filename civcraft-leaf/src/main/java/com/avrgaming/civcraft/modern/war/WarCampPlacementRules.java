package com.avrgaming.civcraft.modern.war;

public final class WarCampPlacementRules {
    private WarCampPlacementRules() {}
    public record Origin(int x,int y,int z,String direction) {}
    public static String direction(float yaw) {
        if(!Float.isFinite(yaw))throw new IllegalArgumentException("Invalid camp yaw");
        double rotation=(yaw-90)%360;if(rotation<0)rotation+=360;
        if(rotation<67.5||rotation>=292.5)return "west";
        if(rotation<112.5)return "north";
        if(rotation<247.5)return "east";
        return "south";
    }
    private static int chunkOrigin(double coordinate){return Math.floorDiv((int)Math.floor(coordinate),16)*16;}
    public static Origin origin(double x,double y,double z,float yaw,int width,int depth) {
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||width<1||width>256||depth<1||depth>256)throw new IllegalArgumentException("Invalid camp origin");
        String direction=direction(yaw);int ox,oz;
        if(direction.equals("east")||direction.equals("west")){ox=chunkOrigin(x);oz=chunkOrigin(z-depth/2.0);if(direction.equals("west"))ox-=width;}
        else {ox=chunkOrigin(x-width/2.0);oz=chunkOrigin(z);if(direction.equals("north"))oz-=depth;}
        return new Origin(ox,(int)Math.floor(y)-1,oz,direction);
    }
    public static void height(double playerY,int originY,int templateHeight,int minimum,int worldMaximum) {
        if(playerY>=200)throw new IllegalArgumentException("Военный лагерь нельзя основать на высоте 200 и выше.");
        if(playerY<minimum||originY<1)throw new IllegalArgumentException("Военный лагерь слишком глубоко под землёй.");
        if(templateHeight<1||(long)originY+templateHeight>=Math.min(255,worldMaximum))throw new IllegalArgumentException("Шаблон военного лагеря слишком высок для этого места.");
    }
    public static void terrain(int originY,double averageHighest) {
        if(!Double.isFinite(averageHighest)||Math.abs(originY-averageHighest)>10)throw new IllegalArgumentException("Военный лагерь должен быть не дальше 10 блоков от средней поверхности.");
    }
}
