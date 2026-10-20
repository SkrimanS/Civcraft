package com.avrgaming.civcraft.modern.wall;

import java.util.*;

/** Wall.java/FortifiedWall.java half-block stepping and material layering. No road slope rule. */
public final class WallRules {
    private WallRules() {}
    public record Point(int x,int y,int z) {}
    /** The original validates the endpoint separately and may omit it on failure. */
    public record Route(List<Point> samples,Point endpoint) {
        public Route{samples=List.copyOf(samples);Objects.requireNonNull(endpoint);}
        public int segments(boolean includeEndpoint){return Math.addExact(samples.size(),includeEndpoint?1:0);}
    }
    public record Block(Point position,String data) {}
    public static Route route(Point firstPlaced,Point secondPlaced,WallCatalog.Profile profile) {
        double dx=(double)secondPlaced.x()-firstPlaced.x(),dy=(double)secondPlaced.y()-firstPlaced.y(),dz=(double)secondPlaced.z()-firstPlaced.z();
        double distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(distance>profile.maximumSegment())throw new IllegalArgumentException("Маркеры стены должны быть не дальше "+profile.maximumSegment()+" блоков друг от друга.");
        if(distance>0){dx=dx/distance*.5;dy=dy/distance*.5;dz=dz/distance*.5;}
        double x=firstPlaced.x(),y=firstPlaced.y(),z=firstPlaced.z();
        Point last=firstPlaced;var samples=new ArrayList<Point>();samples.add(last);int steps=0;
        while(distance(x,y,z,secondPlaced)>1.0) {
            x+=dx;y+=dy;z+=dz;var current=new Point((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));
            if(current.equals(last))continue;
            last=current;
            if(++steps>profile.recursionLimit())throw new IllegalArgumentException("Превышен предел шагов маршрута стены.");
            samples.add(current);
            if(distance(x,y,z,secondPlaced)>distance)break;
        }
        return new Route(samples,secondPlaced);
    }
    private static double distance(double x,double y,double z,Point target) {
        double dx=target.x()-x,dy=target.y()-y,dz=target.z()-z;return Math.sqrt(dx*dx+dy*dy+dz*dz);
    }
    public static List<Block> blocks(Route route,WallCatalog.Profile profile,String style,boolean includeEndpoint) {
        var result=new LinkedHashMap<Point,String>();
        for(var center:route.samples())segment(result,center,profile,style);
        if(includeEndpoint)segment(result,route.endpoint(),profile,style);
        return result.entrySet().stream().map(e->new Block(e.getKey(),e.getValue())).toList();
    }
    private static void segment(Map<Point,String> result,Point center,WallCatalog.Profile profile,String style) {
        int[][] offsets=profile.fortified()?new int[][]{{0,0},{1,0},{-1,0},{0,1},{0,-1}}:new int[][]{{0,0}};
        for(var offset:offsets)for(int layer=0;layer<profile.height();layer++) {
            var point=new Point(Math.addExact(center.x(),offset[0]),Math.addExact(center.y(),layer),Math.addExact(center.z(),offset[1]));
            result.put(point,material(profile,style,layer));
        }
    }
    public static String material(WallCatalog.Profile profile,String style,int layer) {
        if(!profile.fortified())return layer==0?"minecraft:oak_planks":"minecraft:dirt";
        boolean band=layer==0 || layer==profile.height()/2;
        return "minecraft:"+switch(Objects.toString(style,"default")) {
            case "atlantean" -> band?"dark_prismarine":"prismarine_bricks";
            case "arctic" -> band?"packed_ice":"snow_block";
            case "aztec" -> band?"mossy_cobblestone":"cobblestone";
            case "cultist" -> band?"blue_terracotta":"light_blue_terracotta";
            case "egyptian" -> band?"chiseled_sandstone":"cut_sandstone";
            case "elven" -> band?"jungle_log":"jungle_leaves";
            case "hell" -> band?"nether_bricks":"netherrack";
            case "roman" -> band?"chiseled_quartz_block":"quartz_block";
            default -> band?"mossy_stone_bricks":"stone_bricks";
        };
    }
    public static double cost(Route route,WallCatalog.Profile profile,int uniqueBlocks,boolean includeEndpoint) {
        if(uniqueBlocks<0)throw new IllegalArgumentException("Неверное количество блоков стены.");
        double cost=profile.fortified()?profile.segmentCost()*uniqueBlocks/profile.height():profile.segmentCost()*route.segments(includeEndpoint);
        if(!Double.isFinite(cost) || cost<0 || cost>Long.MAX_VALUE)throw new IllegalArgumentException("Некорректная стоимость стены.");
        return profile.fortified()?Math.round(cost):cost;
    }
}
