package com.avrgaming.civcraft.modern.build;

import org.bukkit.Axis;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;

public final class LegacyBlockStateApplier {
    private LegacyBlockStateApplier() {
    }

    public static void apply(Block block, LegacyBlock legacyBlock) {
        int id = legacyBlock.legacyId();
        int data = legacyBlock.legacyData() & 0xF;
        BlockData blockData = block.getBlockData();

        if (blockData instanceof Stairs stairs && isStairs(id)) {
            stairs.setFacing(stairFacing(data));
            stairs.setHalf((data & 0x4) != 0 ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
            block.setBlockData(stairs, false);
            return;
        }

        if (blockData instanceof Slab slab && isSlab(id)) {
            if (isDoubleSlab(id)) {
                slab.setType(Slab.Type.DOUBLE);
            } else {
                slab.setType((data & 0x8) != 0 ? Slab.Type.TOP : Slab.Type.BOTTOM);
            }
            block.setBlockData(slab, false);
            return;
        }

        if (blockData instanceof Orientable orientable && isLog(id)) {
            orientable.setAxis(logAxis(data));
            block.setBlockData(orientable, false);
            return;
        }

        if (blockData instanceof Door door && isDoor(id)) {
            door.setHalf((data & 0x8) != 0 ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
            if ((data & 0x8) == 0) {
                door.setFacing(doorLikeFacing(data));
            }
            block.setBlockData(door, false);
            return;
        }

        if (blockData instanceof Directional directional) {
            BlockFace face = directionalFacing(id, data);
            if (face != null && directional.getFaces().contains(face)) {
                directional.setFacing(face);
                block.setBlockData(directional, false);
            }
        }
    }

    private static boolean isStairs(int id) {
        return switch (id) {
            case 53, 67, 108, 109, 114, 128, 134, 135, 136, 156, 163, 164, 180 -> true;
            default -> false;
        };
    }

    private static boolean isSlab(int id) {
        return switch (id) {
            case 43, 44, 125, 126, 181, 182 -> true;
            default -> false;
        };
    }

    private static boolean isDoubleSlab(int id) {
        return switch (id) {
            case 43, 125, 181 -> true;
            default -> false;
        };
    }

    private static boolean isLog(int id) {
        return id == 17 || id == 162;
    }

    private static boolean isDoor(int id) {
        return id == 64 || id == 71 || id == 193;
    }

    private static BlockFace stairFacing(int data) {
        return switch (data & 0x3) {
            case 0 -> BlockFace.EAST;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.SOUTH;
            case 3 -> BlockFace.NORTH;
            default -> BlockFace.NORTH;
        };
    }

    private static Axis logAxis(int data) {
        return switch (data & 0xC) {
            case 0x4 -> Axis.X;
            case 0x8 -> Axis.Z;
            default -> Axis.Y;
        };
    }

    private static BlockFace directionalFacing(int id, int data) {
        return switch (id) {
            case 23, 54, 61, 62, 65, 68 -> horizontalFacing(data);
            case 50 -> torchFacing(data);
            case 96, 107, 192 -> doorLikeFacing(data);
            default -> null;
        };
    }

    private static BlockFace horizontalFacing(int data) {
        return switch (data) {
            case 2 -> BlockFace.NORTH;
            case 3 -> BlockFace.SOUTH;
            case 4 -> BlockFace.WEST;
            case 5 -> BlockFace.EAST;
            default -> BlockFace.NORTH;
        };
    }

    private static BlockFace torchFacing(int data) {
        return switch (data) {
            case 1 -> BlockFace.EAST;
            case 2 -> BlockFace.WEST;
            case 3 -> BlockFace.SOUTH;
            case 4 -> BlockFace.NORTH;
            default -> BlockFace.NORTH;
        };
    }

    private static BlockFace doorLikeFacing(int data) {
        return switch (data & 0x3) {
            case 0 -> BlockFace.EAST;
            case 1 -> BlockFace.SOUTH;
            case 2 -> BlockFace.WEST;
            case 3 -> BlockFace.NORTH;
            default -> BlockFace.NORTH;
        };
    }
}
