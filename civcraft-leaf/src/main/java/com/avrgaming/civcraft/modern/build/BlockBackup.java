package com.avrgaming.civcraft.modern.build;

public record BlockBackup(String world, int x, int y, int z, String blockData, String blockNbt) {
    public BlockBackup(String world, int x, int y, int z, String blockData) {
        this(world, x, y, z, blockData, null);
    }

    public static BlockBackup capture(org.bukkit.block.Block block) {
        var state = block.getState();
        return new BlockBackup(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                state.getBlockData().getAsString(), com.avrgaming.civcraft.modern.war.BlockEntitySnapshot.read(state));
    }

    public boolean matches(org.bukkit.block.Block block) {
        return blockData.equals(block.getBlockData().getAsString())
                && java.util.Objects.equals(blockNbt,
                    com.avrgaming.civcraft.modern.war.BlockEntitySnapshot.read(block.getState()));
    }

    public void restore(org.bukkit.block.Block block) {
        block.setBlockData(org.bukkit.Bukkit.createBlockData(blockData), false);
        com.avrgaming.civcraft.modern.war.BlockEntitySnapshot.restore(block.getState(), blockNbt);
    }
}
