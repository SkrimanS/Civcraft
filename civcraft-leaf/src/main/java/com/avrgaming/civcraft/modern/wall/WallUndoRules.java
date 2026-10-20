package com.avrgaming.civcraft.modern.wall;

import java.util.*;

/** Source restrictedUndoBlocks affects the refund; Wall.delete restores all recorded cells. */
public final class WallUndoRules {
    private WallUndoRules() {}
    private static final Set<String> RESTRICTED=Set.of(
            "wheat","carrots","potatoes","redstone_wire","redstone_torch","redstone_wall_torch","repeater","comparator",
            "tripwire","tripwire_hook","sugar_cane","short_grass","fern","poppy","blue_orchid","allium","azure_bluet",
            "red_tulip","orange_tulip","white_tulip","pink_tulip","oxeye_daisy","red_mushroom","sunflower","lilac",
            "tall_grass","large_fern","rose_bush","peony","cake","cactus","piston","sticky_piston","piston_head","moving_piston",
            "pumpkin_stem","melon_stem","attached_pumpkin_stem","attached_melon_stem");
    public static String material(String data) {
        int state=data.indexOf('[');String name=state<0?data:data.substring(0,state);return name.substring(name.indexOf(':')+1).toLowerCase(Locale.ROOT);
    }
    public static boolean refundable(String before){String name=material(before);return !RESTRICTED.contains(name) && !name.endsWith("_sapling");}
    public static long refund(List<WallPlacement.Cell> cells,double price,int height) {
        if(height<1 || !Double.isFinite(price) || price<0)throw new IllegalArgumentException("Неверные параметры возврата стоимости стены.");
        double amount=0;for(var cell:cells)if(refundable(cell.before()))amount+=price;
        amount/=height;if(!Double.isFinite(amount) || amount<0 || amount>Long.MAX_VALUE)throw new IllegalArgumentException("Переполнение возврата стоимости стены.");return Math.round(amount);
    }
}
