package com.avrgaming.civcraft.modern.war;

public final class HallValidationRules {
    private HallValidationRules() {}
    public static int reinforcement(String material) {
        return switch(material) {
            case "AIR","CAVE_AIR","VOID_AIR","WATER","LAVA","BUBBLE_COLUMN","COBWEB"->0;
            case "IRON_BLOCK"->4;
            case "STONE_BRICKS","MOSSY_STONE_BRICKS","CRACKED_STONE_BRICKS","CHISELED_STONE_BRICKS"->3;
            case "STONE","GRANITE","DIORITE","ANDESITE","POLISHED_GRANITE","POLISHED_DIORITE","POLISHED_ANDESITE"->2;
            default->1;
        };
    }
    public static double requirement(int depth) {
        // Preserve the actual legacy ordering: its >40 branch is unreachable.
        return depth>10?0.8*0.3:0.8;
    }
    public static boolean layerValid(long reinforcement,long columns,int depth) {
        return columns==0 || reinforcement/(double)columns>=requirement(depth);
    }
}
