package com.avrgaming.civcraft.modern.build;

public record BlockPlacement(String world, int x, int y, int z, String material, java.util.List<String> signLines) {
    public BlockPlacement(String world,int x,int y,int z,String material) {
        this(world,x,y,z,material,java.util.List.of());
    }
}
