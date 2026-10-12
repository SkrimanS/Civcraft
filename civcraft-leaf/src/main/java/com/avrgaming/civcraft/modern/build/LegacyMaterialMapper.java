package com.avrgaming.civcraft.modern.build;

import org.bukkit.Material;

public final class LegacyMaterialMapper {
    private LegacyMaterialMapper() {
    }

    @SuppressWarnings("deprecation")
    public static Material map(int id, byte data) {
        Material material = Material.matchMaterial(String.valueOf(id), true);
        if (material != null && material.isBlock()) {
            return material;
        }
        return switch (id) {
            case 0 -> Material.AIR;
            case 1 -> Material.STONE;
            case 2 -> Material.GRASS_BLOCK;
            case 3 -> Material.DIRT;
            case 4 -> Material.COBBLESTONE;
            case 5 -> plank(data);
            case 7 -> Material.BEDROCK;
            case 8, 9 -> Material.WATER;
            case 10, 11 -> Material.LAVA;
            case 12 -> Material.SAND;
            case 13 -> Material.GRAVEL;
            case 14 -> Material.GOLD_ORE;
            case 15 -> Material.IRON_ORE;
            case 16 -> Material.COAL_ORE;
            case 17 -> log(data);
            case 18 -> leaves(data);
            case 19 -> Material.SPONGE;
            case 20 -> Material.GLASS;
            case 25 -> Material.NOTE_BLOCK;
            case 21 -> Material.LAPIS_ORE;
            case 22 -> Material.LAPIS_BLOCK;
            case 23 -> Material.DISPENSER;
            case 24 -> sandstone(data);
            case 27 -> Material.POWERED_RAIL;
            case 28 -> Material.DETECTOR_RAIL;
            case 29 -> Material.STICKY_PISTON;
            case 26 -> Material.WHITE_BED;
            case 30 -> Material.COBWEB;
            case 32 -> Material.DEAD_BUSH;
            case 33 -> Material.PISTON;
            case 31 -> Material.SHORT_GRASS;
            case 35 -> wool(data);
            case 37 -> Material.DANDELION;
            case 38 -> Material.POPPY;
            case 39 -> Material.BROWN_MUSHROOM;
            case 40 -> Material.RED_MUSHROOM;
            case 41 -> Material.GOLD_BLOCK;
            case 42 -> Material.IRON_BLOCK;
            case 43, 44 -> slab(data);
            case 45 -> Material.BRICKS;
            case 46 -> Material.TNT;
            case 47 -> Material.BOOKSHELF;
            case 48 -> Material.MOSSY_COBBLESTONE;
            case 49 -> Material.OBSIDIAN;
            case 50 -> Material.TORCH;
            case 51 -> Material.FIRE;
            case 52 -> Material.SPAWNER;
            case 53 -> Material.OAK_STAIRS;
            case 54 -> Material.CHEST;
            case 55 -> Material.REDSTONE_WIRE;
            case 56 -> Material.DIAMOND_ORE;
            case 57 -> Material.DIAMOND_BLOCK;
            case 58 -> Material.CRAFTING_TABLE;
            case 59 -> Material.WHEAT;
            case 60 -> Material.FARMLAND;
            case 61, 62 -> Material.FURNACE;
            case 63 -> Material.OAK_SIGN;
            case 64 -> Material.OAK_DOOR;
            case 65 -> Material.LADDER;
            case 66 -> Material.RAIL;
            case 67 -> Material.COBBLESTONE_STAIRS;
            case 68 -> Material.OAK_WALL_SIGN;
            case 69 -> Material.LEVER;
            case 70 -> Material.STONE_PRESSURE_PLATE;
            case 71 -> Material.IRON_DOOR;
            case 72 -> Material.OAK_PRESSURE_PLATE;
            case 73, 74 -> Material.REDSTONE_ORE;
            case 75 -> Material.REDSTONE_TORCH;
            case 76 -> Material.REDSTONE_TORCH;
            case 77 -> Material.STONE_BUTTON;
            case 78 -> Material.SNOW;
            case 79 -> Material.ICE;
            case 80 -> Material.SNOW_BLOCK;
            case 81 -> Material.CACTUS;
            case 82 -> Material.CLAY;
            case 83 -> Material.SUGAR_CANE;
            case 84 -> Material.JUKEBOX;
            case 85 -> Material.OAK_FENCE;
            case 86 -> Material.CARVED_PUMPKIN;
            case 87 -> Material.NETHERRACK;
            case 88 -> Material.SOUL_SAND;
            case 89 -> Material.GLOWSTONE;
            case 90 -> Material.NETHER_PORTAL;
            case 91 -> Material.JACK_O_LANTERN;
            case 92 -> Material.CAKE;
            case 93, 94 -> Material.REPEATER;
            case 95 -> stainedGlass(data);
            case 96 -> Material.OAK_TRAPDOOR;
            case 97 -> Material.INFESTED_STONE;
            case 98 -> stoneBricks(data);
            case 99 -> Material.BROWN_MUSHROOM_BLOCK;
            case 100 -> Material.RED_MUSHROOM_BLOCK;
            case 101 -> Material.IRON_BARS;
            case 102 -> Material.GLASS_PANE;
            case 103 -> Material.MELON;
            case 104 -> Material.PUMPKIN_STEM;
            case 105 -> Material.MELON_STEM;
            case 106 -> Material.VINE;
            case 107 -> Material.OAK_FENCE_GATE;
            case 108 -> Material.BRICK_STAIRS;
            case 109 -> Material.STONE_BRICK_STAIRS;
            case 110 -> Material.MYCELIUM;
            case 111 -> Material.LILY_PAD;
            case 112 -> Material.NETHER_BRICKS;
            case 113 -> Material.NETHER_BRICK_FENCE;
            case 114 -> Material.NETHER_BRICK_STAIRS;
            case 115 -> Material.NETHER_WART;
            case 116 -> Material.ENCHANTING_TABLE;
            case 117 -> Material.BREWING_STAND;
            case 118 -> Material.CAULDRON;
            case 119 -> Material.END_PORTAL;
            case 120 -> Material.END_PORTAL_FRAME;
            case 121 -> Material.END_STONE;
            case 122 -> Material.DRAGON_EGG;
            case 123, 124 -> Material.REDSTONE_LAMP;
            case 125, 126 -> Material.OAK_SLAB;
            case 128 -> Material.SANDSTONE_STAIRS;
            case 130 -> Material.ENDER_CHEST;
            case 129 -> Material.EMERALD_ORE;
            case 131 -> Material.TRIPWIRE_HOOK;
            case 132 -> Material.TRIPWIRE;
            case 133 -> Material.EMERALD_BLOCK;
            case 134 -> Material.SPRUCE_STAIRS;
            case 135 -> Material.BIRCH_STAIRS;
            case 136 -> Material.JUNGLE_STAIRS;
            case 139 -> Material.COBBLESTONE_WALL;
            case 137 -> Material.COMMAND_BLOCK;
            case 140 -> Material.FLOWER_POT;
            case 141 -> Material.CARROTS;
            case 142 -> Material.POTATOES;
            case 143 -> Material.OAK_BUTTON;
            case 144 -> Material.SKELETON_SKULL;
            case 145 -> Material.ANVIL;
            case 146 -> Material.TRAPPED_CHEST;
            case 147 -> Material.LIGHT_WEIGHTED_PRESSURE_PLATE;
            case 148 -> Material.HEAVY_WEIGHTED_PRESSURE_PLATE;
            case 149, 150 -> Material.COMPARATOR;
            case 151, 178 -> Material.DAYLIGHT_DETECTOR;
            case 152 -> Material.REDSTONE_BLOCK;
            case 153 -> Material.NETHER_QUARTZ_ORE;
            case 154 -> Material.HOPPER;
            case 155 -> quartz(data);
            case 156 -> Material.QUARTZ_STAIRS;
            case 157 -> Material.ACTIVATOR_RAIL;
            case 158 -> Material.DROPPER;
            case 159 -> terracotta(data);
            case 160 -> stainedGlassPane(data);
            case 161 -> Material.ACACIA_LEAVES;
            case 162 -> Material.ACACIA_LOG;
            case 163 -> Material.ACACIA_STAIRS;
            case 164 -> Material.DARK_OAK_STAIRS;
            case 165 -> Material.SLIME_BLOCK;
            case 166 -> Material.BARRIER;
            case 167 -> Material.IRON_TRAPDOOR;
            case 168 -> prismarine(data);
            case 169 -> Material.SEA_LANTERN;
            case 170 -> Material.HAY_BLOCK;
            case 171 -> carpet(data);
            case 172 -> Material.TERRACOTTA;
            case 173 -> Material.COAL_BLOCK;
            case 174 -> Material.PACKED_ICE;
            case 175 -> Material.SUNFLOWER;
            case 176 -> Material.WHITE_BANNER;
            case 177 -> Material.WHITE_WALL_BANNER;
            case 179 -> Material.RED_SANDSTONE;
            case 180 -> Material.RED_SANDSTONE_STAIRS;
            case 181, 182 -> Material.RED_SANDSTONE_SLAB;
            case 188 -> Material.SPRUCE_FENCE;
            case 190 -> Material.DARK_OAK_FENCE;
            case 191 -> Material.ACACIA_FENCE;
            case 192 -> Material.DARK_OAK_FENCE_GATE;
            case 193 -> Material.SPRUCE_DOOR;
            case 183 -> Material.SPRUCE_FENCE_GATE;
            case 184 -> Material.BIRCH_FENCE_GATE;
            case 185 -> Material.JUNGLE_FENCE_GATE;
            case 186 -> Material.DARK_OAK_FENCE_GATE;
            case 187 -> Material.ACACIA_FENCE_GATE;
            case 189 -> Material.BIRCH_FENCE;
            case 194 -> Material.BIRCH_DOOR;
            case 195 -> Material.JUNGLE_DOOR;
            case 196 -> Material.ACACIA_DOOR;
            case 197 -> Material.DARK_OAK_DOOR;
            case 198 -> Material.END_ROD;
            case 199 -> Material.CHORUS_PLANT;
            case 200 -> Material.CHORUS_FLOWER;
            default -> Material.STONE;
        };
    }

    private static Material plank(byte data) {
        return switch (data & 7) { case 1 -> Material.SPRUCE_PLANKS; case 2 -> Material.BIRCH_PLANKS; case 3 -> Material.JUNGLE_PLANKS; case 4 -> Material.ACACIA_PLANKS; case 5 -> Material.DARK_OAK_PLANKS; default -> Material.OAK_PLANKS; };
    }

    private static Material log(byte data) {
        return switch (data & 3) { case 1 -> Material.SPRUCE_LOG; case 2 -> Material.BIRCH_LOG; case 3 -> Material.JUNGLE_LOG; default -> Material.OAK_LOG; };
    }

    private static Material leaves(byte data) {
        return switch (data & 3) { case 1 -> Material.SPRUCE_LEAVES; case 2 -> Material.BIRCH_LEAVES; case 3 -> Material.JUNGLE_LEAVES; default -> Material.OAK_LEAVES; };
    }

    private static Material sandstone(byte data) { return data == 1 ? Material.CHISELED_SANDSTONE : data == 2 ? Material.CUT_SANDSTONE : Material.SANDSTONE; }
    private static Material stoneBricks(byte data) { return switch (data & 3) { case 1 -> Material.MOSSY_STONE_BRICKS; case 2 -> Material.CRACKED_STONE_BRICKS; case 3 -> Material.CHISELED_STONE_BRICKS; default -> Material.STONE_BRICKS; }; }
    private static Material quartz(byte data) { return data == 1 ? Material.CHISELED_QUARTZ_BLOCK : data == 2 ? Material.QUARTZ_PILLAR : Material.QUARTZ_BLOCK; }
    private static Material prismarine(byte data) { return data == 1 ? Material.PRISMARINE_BRICKS : data == 2 ? Material.DARK_PRISMARINE : Material.PRISMARINE; }
    private static Material slab(byte data) { return switch (data & 7) { case 1 -> Material.SANDSTONE_SLAB; case 2 -> Material.OAK_SLAB; case 3 -> Material.COBBLESTONE_SLAB; case 4 -> Material.BRICK_SLAB; case 5 -> Material.STONE_BRICK_SLAB; case 6 -> Material.NETHER_BRICK_SLAB; case 7 -> Material.QUARTZ_SLAB; default -> Material.SMOOTH_STONE_SLAB; }; }
    private static Material wool(byte data) { return colored(data, "WOOL"); }
    private static Material carpet(byte data) { return colored(data, "CARPET"); }
    private static Material terracotta(byte data) { return colored(data, "TERRACOTTA"); }
    private static Material stainedGlass(byte data) { return colored(data, "STAINED_GLASS"); }
    private static Material stainedGlassPane(byte data) { return colored(data, "STAINED_GLASS_PANE"); }

    private static Material colored(byte data, String suffix) {
        String color = switch (data & 15) {
            case 1 -> "ORANGE"; case 2 -> "MAGENTA"; case 3 -> "LIGHT_BLUE"; case 4 -> "YELLOW";
            case 5 -> "LIME"; case 6 -> "PINK"; case 7 -> "GRAY"; case 8 -> "LIGHT_GRAY";
            case 9 -> "CYAN"; case 10 -> "PURPLE"; case 11 -> "BLUE"; case 12 -> "BROWN";
            case 13 -> "GREEN"; case 14 -> "RED"; case 15 -> "BLACK"; default -> "WHITE";
        };
        Material material = Material.matchMaterial(color + "_" + suffix);
        return material == null ? Material.STONE : material;
    }
}
